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
// Resolve a creator profile by its display name, for linking bot-posted schematics.
const codeByDisplayName = db.prepare('SELECT id FROM codes WHERE display_name = ? AND revoked = 0 ORDER BY id LIMIT 1');
const insertPost = db.prepare(`
  INSERT INTO posts
    (id, title, thumbnail_name, poster, designer, category, size_x, size_y, size_z, block_count,
     downloads, likes, posted_at, description, thumbnail_key, file_key, file_hash, file_size,
     visibility, uploader_code_id, created_token, created_ip, report_count, content_hash)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, 'visible', ?, ?, ?, 0, ?)
`);

// A hash of only the block data (Regions), so it identifies a schematic by its actual arrangement
// of blocks - independent of the Name/Author/Description/timestamps we (and the uploader) stamp.
async function contentHash(buffer) {
  try {
    const { parsed } = await nbt.parse(buffer);
    const regions = parsed.value?.Regions;

    if (!regions) return null;

    const regionsOnly = { name: '', type: 'compound', value: { Regions: regions } };
    const bytes = nbt.writeUncompressed(regionsOnly, 'big');
    return crypto.createHash('sha256').update(bytes).digest('hex');
  } catch {
    return null;
  }
}

const postByContentHash = db.prepare("SELECT id FROM posts WHERE content_hash = ? AND visibility = 'visible'");
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

// The Discord bot posts on behalf of a member using a shared secret instead of an uploader code.
function requireBotKey(req, res, next) {
  const expected = process.env.BOT_UPLOAD_KEY;

  if (!expected) return res.status(503).json({ error: 'no_bot_key' });
  if ((req.get('X-Bot-Key') || '') !== expected) return res.status(403).json({ error: 'bad_bot_key' });

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

// One-off backfill so existing posts get a content hash and can be matched against new uploads.
async function migrateContentHashes() {
  const rows = db.prepare('SELECT id, file_key FROM posts WHERE content_hash IS NULL AND file_key IS NOT NULL').all();

  for (const row of rows) {
    try {
      const buffer = await fs.promises.readFile(path.join(paths.files, row.file_key));
      const hash = await contentHash(buffer);

      if (hash) db.prepare('UPDATE posts SET content_hash = ? WHERE id = ?').run(hash, row.id);
    } catch {
      // Skip unreadable files.
    }
  }
}

export function registerUploadRoutes(app) {
  migrateContentHashes().catch(() => {});

  app.get('/uploader', (req, res) => {
    const code = (req.get('X-Upload-Code') || '').trim();
    const row = code && codeByValue.get(code);

    if (!row) {
      return res.status(403).json({ valid: false });
    }

    res.json({ valid: true, displayName: row.display_name, ign: row.ign || '' });
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
    const series = {
      views: labels.map(() => 0),
      downloads: labels.map(() => 0),
      likes: labels.map(() => 0),
      stars: labels.map(() => 0),
    };
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
    // Stars series is the total stars given per day (sum of ratings), not the count of raters.
    fill(series.stars, db.prepare("SELECT date(created_at/1000,'unixepoch') day, CAST(ROUND(SUM(value)/2.0) AS INTEGER) n FROM stars WHERE created_at >= ? AND post_id IN (SELECT id FROM posts WHERE uploader_code_id = ?) GROUP BY day").all(sinceMs, codeId));

    // Per-post daily series so clicking a post on the dashboard can graph just that post.
    const postSeries = {};
    for (const p of posts) {
      postSeries[p.id] = {
        views: labels.map(() => 0),
        downloads: labels.map(() => 0),
        likes: labels.map(() => 0),
        stars: labels.map(() => 0),
      };
    }
    const fillPost = (key, rows) => {
      for (const r of rows) {
        const s = postSeries[r.post_id];
        if (s && r.day in index) s[key][index[r.day]] = r.n;
      }
    };

    if (posts.length) {
      fillPost('downloads', db.prepare("SELECT post_id, day, COUNT(*) n FROM downloads WHERE day >= ? AND post_id IN (SELECT id FROM posts WHERE uploader_code_id = ?) GROUP BY post_id, day").all(since, codeId));
      fillPost('views', db.prepare("SELECT post_id, day, COUNT(*) n FROM views WHERE day >= ? AND post_id IN (SELECT id FROM posts WHERE uploader_code_id = ?) GROUP BY post_id, day").all(since, codeId));
      fillPost('likes', db.prepare("SELECT post_id, date(created_at/1000,'unixepoch') day, COUNT(*) n FROM likes WHERE created_at >= ? AND post_id IN (SELECT id FROM posts WHERE uploader_code_id = ?) GROUP BY post_id, day").all(sinceMs, codeId));
      fillPost('stars', db.prepare("SELECT post_id, date(created_at/1000,'unixepoch') day, CAST(ROUND(SUM(value)/2.0) AS INTEGER) n FROM stars WHERE created_at >= ? AND post_id IN (SELECT id FROM posts WHERE uploader_code_id = ?) GROUP BY post_id, day").all(sinceMs, codeId));
    }

    res.json({
      poster,
      totals: {
        posts: posts.length,
        views: posts.reduce((a, p) => a + p.views, 0),
        downloads: posts.reduce((a, p) => a + p.downloads, 0),
        likes: posts.reduce((a, p) => a + p.likes, 0),
        followers: db.prepare('SELECT COUNT(*) n FROM follows WHERE poster = ?').get(poster).n,
        rating: db.prepare('SELECT COALESCE(AVG(value), 0) a FROM stars WHERE post_id IN (SELECT id FROM posts WHERE uploader_code_id = ?)').get(codeId).a / 2,
        ratingCount: db.prepare('SELECT COUNT(*) n FROM stars WHERE post_id IN (SELECT id FROM posts WHERE uploader_code_id = ?)').get(codeId).n,
      },
      days: labels,
      series,
      postSeries,
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

  // Recent follows to this uploader and likes on their posts, newest first. The mod tracks
  // which it has already shown locally so it only toasts genuinely new ones.
  app.get('/me/notifications', requireCode, (req, res) => {
    const codeId = req.uploaderCode.id;
    const poster = req.uploaderCode.display_name;

    const follows = db.prepare('SELECT created_at FROM follows WHERE poster = ? ORDER BY created_at DESC LIMIT 30')
      .all(poster).map((f) => ({ type: 'follow', at: f.created_at }));

    const likes = db.prepare(`
      SELECT l.created_at AS at, l.post_id AS postId, p.title AS postTitle
      FROM likes l JOIN posts p ON p.id = l.post_id
      WHERE p.uploader_code_id = ? ORDER BY l.created_at DESC LIMIT 30
    `).all(codeId).map((l) => ({ type: 'like', at: l.at, postId: l.postId, postTitle: l.postTitle }));

    const items = [...follows, ...likes].sort((a, b) => b.at - a.at).slice(0, 40);
    res.json({ items });
  });

  // Edit only the text of a post you own (never the images or schematic).
  app.post('/me/posts/:id/edit', requireCode, (req, res) => {
    const post = db.prepare('SELECT * FROM posts WHERE id = ?').get(req.params.id);

    if (!post || post.uploader_code_id !== req.uploaderCode.id) {
      return res.status(404).json({ error: 'not_found', message: 'No such post of yours.' });
    }

    const title = String(req.body?.title || '').trim();
    const thumbnailName = String(req.body?.thumbnailName || '').trim();
    const designer = String(req.body?.designer || '').trim();
    const description = String(req.body?.description || '').trim();
    const category = String(req.body?.category || '').toUpperCase();

    if (!title) return res.status(400).json({ error: 'no_title', message: 'A title is required.' });
    if (!CATEGORIES.has(category)) return res.status(400).json({ error: 'bad_category', message: 'Unknown category.' });

    db.prepare('UPDATE posts SET title = ?, thumbnail_name = ?, designer = ?, description = ?, category = ? WHERE id = ?')
      .run(title, thumbnailName, designer, description, category, post.id);
    invalidatePosts();
    res.json({ ok: true });
  });

  // Unpublish (hide) a post you own.
  app.post('/me/posts/:id/unpublish', requireCode, (req, res) => {
    const post = db.prepare('SELECT * FROM posts WHERE id = ?').get(req.params.id);

    if (!post || post.uploader_code_id !== req.uploaderCode.id) {
      return res.status(404).json({ error: 'not_found', message: 'No such post of yours.' });
    }

    db.prepare("UPDATE posts SET visibility = 'hidden' WHERE id = ?").run(post.id);
    invalidatePosts();
    res.json({ ok: true });
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

      // Reject a schematic whose block data already exists, regardless of its name/uploader.
      const cHash = await contentHash(original);

      if (cHash && postByContentHash.get(cHash)) {
        return fail(409, 'duplicate', 'This schematic has already been uploaded, you cannot post it again.');
      }

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

      // The uploader can pick which image is the card thumbnail (defaults to the first).
      const thumbIndex = Math.max(0, Math.min(imageKeys.length - 1, Math.round(Number(meta.thumbnailIndex) || 0)));

      insertPost.run(
        id, title, String(meta.thumbnailName || '').trim() || null, req.uploaderCode.display_name,
        String(meta.designer || '').trim() || null, category, dims.x, dims.y, dims.z, dims.blocks,
        Date.now(), String(meta.description || '').trim() || null, imageKeys[thumbIndex], fileKey, fileHash,
        stamped.length, req.uploaderCode.id, (req.get('X-Device-Token') || '').trim() || null,
        req.ip || null, cHash,
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

  // Same upload pipeline as /upload, but the Discord bot supplies the poster/designer directly
  // (there is no uploader code) and authenticates with the shared bot key.
  app.post('/bot/upload', requireBotKey, upload, async (req, res) => {
    const schematic = req.files?.schematic?.[0];
    const images = req.files?.images ?? [];
    const temps = [schematic, ...images].filter(Boolean).map((f) => f.path);
    const cleanup = () => Promise.all(temps.map((p) => fs.promises.unlink(p).catch(() => {})));
    const fail = (status, error, message) => { cleanup(); return res.status(status).json({ error, message }); };

    try {
      const poster = String(req.body.poster || '').trim();
      const title = String(req.body.title || '').trim();
      const category = String(req.body.category || '').toUpperCase();

      if (!poster) return fail(400, 'bad_poster', 'A poster is required.');
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

      // Reject a schematic whose block data already exists, regardless of its name/uploader.
      const cHash = await contentHash(original);

      if (cHash && postByContentHash.get(cHash)) {
        return fail(409, 'duplicate', 'This schematic has already been uploaded.');
      }

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

      // Link to a creator profile when the bot marks this as a verified profile
      // link (the poster's Discord id maps to this catalogue display name).
      const profileName = String(req.body.profile || '').trim();
      const codeRow = profileName ? codeByDisplayName.get(profileName) : null;
      const uploaderCodeId = codeRow ? codeRow.id : null;

      insertPost.run(
        id, title, null, poster,
        String(req.body.designer || '').trim() || null, category, dims.x, dims.y, dims.z, dims.blocks,
        Date.now(), String(req.body.description || '').trim() || null, imageKeys[0], fileKey, fileHash,
        stamped.length, uploaderCodeId, (req.get('X-Device-Token') || '').trim() || null,
        req.ip || null, cHash,
      );

      imageKeys.forEach((key, i) => insertImage.run(id, i, key));
      invalidatePosts();

      const row = db.prepare('SELECT * FROM posts WHERE id = ?').get(id);
      res.status(201).json(serializePost(row, ''));
    } catch (e) {
      cleanup();
      console.error('[bot/upload] failed', e);
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
