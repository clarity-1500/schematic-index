import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import multer from 'multer';
import nbt from 'prismarine-nbt';
import { db } from './db.js';
import { paths } from './config.js';
import { serializePost } from './serialize.js';

const CATEGORIES = new Set([
  'FARMS', 'CONTRAPTIONS', 'REGEARS', 'STASHES', 'GAMBLING_BASES', 'HANGOUT_BASES', 'MEGA_BUILDS',
]);

const MAX_SCHEMATIC = 20 * 1024 * 1024;
const MAX_IMAGE = 8 * 1024 * 1024;
const MAX_IMAGES = 5;

const upload = multer({
  storage: multer.memoryStorage(),
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

  app.post('/upload', requireCode, upload, async (req, res) => {
    const schematic = req.files?.schematic?.[0];
    const images = req.files?.images ?? [];

    let meta;
    try {
      meta = JSON.parse(req.body.meta || '{}');
    } catch {
      return res.status(400).json({ error: 'bad_meta', message: 'meta must be valid JSON.' });
    }

    const title = String(meta.title || '').trim();
    const category = String(meta.category || '').toUpperCase();

    if (!schematic) return res.status(400).json({ error: 'no_schematic', message: 'A .litematic file is required.' });
    if (!schematic.originalname.toLowerCase().endsWith('.litematic')) {
      return res.status(400).json({ error: 'bad_file', message: 'The schematic must be a .litematic file.' });
    }
    if (!title) return res.status(400).json({ error: 'no_title', message: 'A title is required.' });
    if (!CATEGORIES.has(category)) return res.status(400).json({ error: 'bad_category', message: 'Unknown category.' });
    if (images.length < 1 || images.length > MAX_IMAGES) {
      return res.status(400).json({ error: 'bad_images', message: `Attach 1 to ${MAX_IMAGES} images.` });
    }
    for (const img of images) {
      if (!imageExtension(img.mimetype)) {
        return res.status(400).json({ error: 'bad_image_type', message: 'Images must be PNG or JPG.' });
      }
      if (img.size > MAX_IMAGE) {
        return res.status(413).json({ error: 'image_too_large', message: 'An image is over 8 MB.' });
      }
    }

    const id = crypto.randomBytes(8).toString('hex');
    const dims = await readLitematic(schematic.buffer);

    const fileKey = `sch/${id}.litematic`;
    fs.writeFileSync(path.join(paths.files, fileKey), schematic.buffer);
    const fileHash = 'sha256:' + crypto.createHash('sha256').update(schematic.buffer).digest('hex');

    const imageKeys = images.map((img, i) => {
      const key = `img/${id}_${i}.${imageExtension(img.mimetype)}`;
      fs.writeFileSync(path.join(paths.files, key), img.buffer);
      return key;
    });

    insertPost.run(
      id, title, String(meta.thumbnailName || '').trim() || null, req.uploaderCode.display_name,
      String(meta.designer || '').trim() || null, category, dims.x, dims.y, dims.z, dims.blocks,
      Date.now(), String(meta.description || '').trim() || null, imageKeys[0], fileKey, fileHash,
      schematic.buffer.length, req.uploaderCode.id, (req.get('X-Device-Token') || '').trim() || null,
      req.ip || null,
    );

    imageKeys.forEach((key, i) => insertImage.run(id, i, key));

    const row = db.prepare('SELECT * FROM posts WHERE id = ?').get(id);
    res.status(201).json(serializePost(row, ''));
  });

  app.use((err, req, res, next) => {
    if (err instanceof multer.MulterError) {
      return res.status(413).json({ error: 'upload_error', message: err.message });
    }
    next(err);
  });
}
