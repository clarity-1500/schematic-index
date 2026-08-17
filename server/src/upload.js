import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import multer from 'multer';
import nbt from 'prismarine-nbt';
import { db } from './db.js';
import { paths, fileUrl } from './config.js';
import { serializePost } from './serialize.js';
import { invalidatePosts } from './cache.js';
import { stampLitematic } from './stamp.js';

const CATEGORIES = new Set([
  'FARMS', 'CONTRAPTIONS', 'REGEARS', 'STASHES', 'GAMBLING_BASES', 'HANGOUT_BASES', 'MEGA_BUILDS',
]);

const MAX_SCHEMATIC = 50 * 1024 * 1024;
const MAX_IMAGE = 25 * 1024 * 1024;
const MAX_IMAGES = 5;

fs.mkdirSync(paths.uploadsTmp, { recursive: true });

// Stream uploads straight to disk instead of buffering them in memory, so a large
// upload never blocks the event loop or spikes memory for every other request.
const upload = multer({
  storage: multer.diskStorage({
    destination: paths.uploadsTmp,
    filename: (req, file, cb) => cb(null, crypto.randomBytes(16).toString('hex')),
  }),
  limits: { fileSize: MAX_SCHEMATIC, files: MAX_IMAGES + 1 },
}).fields([
  { name: 'schematic', maxCount: 1 },
  { name: 'images', maxCount: MAX_IMAGES },
]);

const codeByValue = db.prepare('SELECT * FROM codes WHERE code = ? AND revoked = 0');
const insertPost = db.prepare(`
  INSERT INTO posts
    (id, title, thumbnail_name, poster, designer, category, size_x, size_y, size_z, block_count,
     downloads, likes, posted_at, description, thumbnail_key, file_key, file_hash, file_size,
     visibility, uploader_code_id, created_token, created_ip, report_count)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, 'visible', ?, ?, ?, 0)
`);
const insertImage = db.prepare('INSERT INTO post_images (post_id, position, file_key) VALUES (?, ?, ?)');

function requireCode(req, res, next) {
  const code = (req.get('X-Upload-Code') || '').trim();
  const row = code && codeByValue.get(code);

  if (!row) {
    return res.status(403).json({ error: 'bad_code', message: 'Invalid or revoked upload code.' });
  }

  req.uploaderCode = row;
  next();
}

async function readLitematic(buffer) {
  try {
    const { parsed } = await nbt.parse(buffer);
    const meta = parsed.value.Metadata?.value ?? {};
    const size = meta.EnclosingSize?.value ?? {};
    return {
      x: Math.abs(Number(size.x?.value ?? 0)),
      y: Math.abs(Number(size.y?.value ?? 0)),
      z: Math.abs(Number(size.z?.value ?? 0)),
      blocks: Math.max(0, Number(meta.TotalBlocks?.value ?? 0)),
    };
  } catch {
    return { x: 0, y: 0, z: 0, blocks: 0 };
  }
}

function imageExtension(mime) {
  if (mime === 'image/png') return 'png';
  if (mime === 'image/jpeg') return 'jpg';
  return null;
}

export function registerUploadRoutes(app) {
  app.get('/uploader', (req, res) => {
    const code = (req.get('X-Upload-Code') || '').trim();
    const row = code && codeByValue.get(code);

    if (!row) {
      return res.status(403).json({ valid: false });
    }

    res.json({ valid: true, displayName: row.display_name });
  });

  app.get('/me/stats', requireCode, (req, res) => {
    const codeId = req.uploaderCode.id;
    const poster = req.uploaderCode.display_name;
    const days = Math.min(365, Math.max(7, Number(req.query.days) || 30));

    const labels = [];
    const index = {};
    const now = Date.now();

    for (let i = days - 1; i >= 0; i--) {
      const day = new Date(now - i * 86400000).toISOString().slice(0, 10);
      index[day] = labels.length;
      labels.push(day);
    }

    const since = labels[0];
    const sinceMs = Date.parse(since + 'T00:00:00.000Z');
    const series = { views: labels.map(() => 0), downloads: labels.map(() => 0), likes: labels.map(() => 0) };
    const fill = (arr, rows) => {
      for (const r of rows) {
        if (r.day in index) arr[index[r.day]] = r.n;
      }
    };

    const posts = db.prepare(`
      SELECT p.id, p.title, p.thumbnail_name, p.poster, p.category, p.downloads, p.likes, p.posted_at, p.thumbnail_key,
             (SELECT COUNT(*) FROM views v WHERE v.post_id = p.id) AS views
      FROM posts p WHERE p.uploader_code_id = ? AND p.visibility = 'visible'
      ORDER BY p.posted_at DESC
    `).all(codeId);

    fill(series.downloads, db.prepare("SELECT day, COUNT(*) n FROM downloads WHERE day >= ? AND post_id IN (SELECT id FROM posts WHERE uploader_code_id = ?) GROUP BY day").all(since, codeId));
    fill(series.views, db.prepare("SELECT day, COUNT(*) n FROM views WHERE day >= ? AND post_id IN (SELECT id FROM posts WHERE uploader_code_id = ?) GROUP BY day").all(since, codeId));
    fill(series.likes, db.prepare("SELECT date(created_at/1000,'unixepoch') day, COUNT(*) n FROM likes WHERE created_at >= ? AND post_id IN (SELECT id FROM posts WHERE uploader_code_id = ?) GROUP BY day").all(sinceMs, codeId));

    res.json({
      poster,
      totals: {
        posts: posts.length,
        views: posts.reduce((a, p) => a + p.views, 0),
        downloads: posts.reduce((a, p) => a + p.downloads, 0),
        likes: posts.reduce((a, p) => a + p.likes, 0),
        followers: db.prepare('SELECT COUNT(*) n FROM follows WHERE poster = ?').get(poster).n,
      },
      days: labels,
      series,
      posts: posts.map((p) => ({
        id: p.id,
        title: p.title,
        thumbnailName: p.thumbnail_name || '',
        poster: p.poster,
        category: p.category,
        views: p.views,
        downloads: p.downloads,
        likes: p.likes,
        postedAt: p.posted_at,
        thumbnailUrl: fileUrl(p.thumbnail_key),
      })),
    });
  });

  app.post('/upload', requireCode, upload, async (req, res) => {
    const schematic = req.files?.schematic?.[0];
    const images = req.files?.images ?? [];
    const temps = [schematic, ...images].filter(Boolean).map((f) => f.path);
    const cleanup = () => Promise.all(temps.map((p) => fs.promises.unlink(p).catch(() => {})));
    const fail = (status, error, message) => { cleanup(); return res.status(status).json({ error, message }); };

    try {
      let meta;
      try {
        meta = JSON.parse(req.body.meta || '{}');
      } catch {
        return fail(400, 'bad_meta', 'meta must be valid JSON.');
      }

      const title = String(meta.title || '').trim();
      const category = String(meta.category || '').toUpperCase();

      if (!schematic) return fail(400, 'no_schematic', 'A .litematic file is required.');
      if (!schematic.originalname.toLowerCase().endsWith('.litematic')) return fail(400, 'bad_file', 'The schematic must be a .litematic file.');
      if (!title) return fail(400, 'no_title', 'A title is required.');
      if (!CATEGORIES.has(category)) return fail(400, 'bad_category', 'Unknown category.');
      if (images.length < 1 || images.length > MAX_IMAGES) return fail(400, 'bad_images', `Attach 1 to ${MAX_IMAGES} images.`);

      for (const img of images) {
        if (!imageExtension(img.mimetype)) return fail(400, 'bad_image_type', 'Images must be PNG or JPG.');
        if (img.size > MAX_IMAGE) return fail(413, 'image_too_large', 'An image is over 25 MB.');
      }

      const id = crypto.randomBytes(8).toString('hex');
      const fileKey = `sch/${id}.litematic`;

      const original = await fs.promises.readFile(schematic.path);
      const dims = await readLitematic(original);
      const stamped = await stampLitematic(original, title);
      const fileHash = 'sha256:' + crypto.createHash('sha256').update(stamped).digest('hex');

      await fs.promises.writeFile(path.join(paths.files, fileKey), stamped);
      await fs.promises.unlink(schematic.path).catch(() => {});

      const imageKeys = [];
      for (let i = 0; i < images.length; i++) {
        const key = `img/${id}_${i}.${imageExtension(images[i].mimetype)}`;
        await fs.promises.rename(images[i].path, path.join(paths.files, key));
        imageKeys.push(key);
      }

      insertPost.run(
        id, title, String(meta.thumbnailName || '').trim() || null, req.uploaderCode.display_name,
        String(meta.designer || '').trim() || null, category, dims.x, dims.y, dims.z, dims.blocks,
        Date.now(), String(meta.description || '').trim() || null, imageKeys[0], fileKey, fileHash,
        stamped.length, req.uploaderCode.id, (req.get('X-Device-Token') || '').trim() || null,
        req.ip || null,
      );

      imageKeys.forEach((key, i) => insertImage.run(id, i, key));
      invalidatePosts();

      const row = db.prepare('SELECT * FROM posts WHERE id = ?').get(id);
      res.status(201).json(serializePost(row, ''));
    } catch (e) {
      cleanup();
      console.error('[upload] failed', e);
      res.status(500).json({ error: 'upload_failed', message: 'The upload could not be processed.' });
    }
  });

  app.use((err, req, res, next) => {
    if (err instanceof multer.MulterError) {
      return res.status(413).json({ error: 'upload_error', message: err.message });
    }
    next(err);
  });
}
