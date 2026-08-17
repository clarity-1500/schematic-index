// Bulk uploader for seeding the catalogue. Drop one folder per post into a `bulk`
// directory; the folder name becomes the title. Each folder holds the .litematic file,
// its images (png/jpg), and an optional post.json for per-post metadata. A defaults.json
// at the root supplies the server URL, upload code and default category/designer.
//
//   bulk/
//     defaults.json          { "server": "...", "code": "ABC123", "category": "FARMS", "designer": "Fudgedy" }
//     Sugarcane Farm/
//       build.litematic
//       1.png  2.png         (optional - a placeholder is used if none)
//       post.json            (optional - { "category": "...", "designer": "...", "description": "..." })
//     TNT Duper/ ...
//
// Run:  node scripts/bulk-upload.js [bulkDir]

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const bulkDir = path.resolve(process.argv[2] || path.join(scriptDir, 'bulk'));

const CATEGORIES = new Set([
  'FARMS', 'CONTRAPTIONS', 'REGEARS', 'STASHES', 'GAMBLING_BASES', 'HANGOUT_BASES', 'MEGA_BUILDS',
]);

const IMAGE_EXT = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg' };

function die(message) {
  console.error('\n' + message + '\n');
  process.exit(1);
}

// --- minimal solid-colour PNG so a post can be uploaded without a real screenshot ---
function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = (c & 1) ? (c >>> 1) ^ 0xEDB88320 : c >>> 1;
  }
  return ~c >>> 0;
}

function pngChunk(type, data) {
  const typeBuf = Buffer.from(type, 'ascii');
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])));
  return Buffer.concat([len, typeBuf, data, crc]);
}

function placeholderPng(w = 320, h = 180, rgb = [26, 30, 35]) {
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  const row = Buffer.alloc(1 + w * 3);
  for (let x = 0; x < w; x++) {
    row[1 + x * 3] = rgb[0];
    row[1 + x * 3 + 1] = rgb[1];
    row[1 + x * 3 + 2] = rgb[2];
  }
  const raw = Buffer.concat(Array.from({ length: h }, () => row));
  const idat = zlib.deflateSync(raw);
  return Buffer.concat([sig, pngChunk('IHDR', ihdr), pngChunk('IDAT', idat), pngChunk('IEND', Buffer.alloc(0))]);
}

function readJson(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return fallback;
  }
}

async function main() {
  if (!fs.existsSync(bulkDir)) {
    die(`No bulk folder at ${bulkDir}\nCreate it, add a defaults.json and one folder per post, then re-run.`);
  }

  const defaults = readJson(path.join(bulkDir, 'defaults.json'), null);

  if (!defaults || !defaults.code) {
    die(`Missing ${path.join(bulkDir, 'defaults.json')} with at least a "code".\n`
      + 'Example: { "server": "https://schematic-index-production.up.railway.app", "code": "YOURCODE", "category": "FARMS", "designer": "Fudgedy" }');
  }

  const server = (defaults.server || 'https://schematic-index-production.up.railway.app').replace(/\/+$/, '');
  const code = String(defaults.code).trim();
  const deviceToken = 'bulk-' + crypto.randomBytes(6).toString('hex');

  // Confirm the code is valid before uploading anything.
  const check = await fetch(server + '/uploader', { headers: { 'X-Upload-Code': code } }).then((r) => r.json()).catch(() => null);

  if (!check || !check.valid) {
    die(`Upload code "${code}" was rejected by ${server}. Generate/copy a valid code in the admin panel.`);
  }

  console.log(`Uploading as "${check.displayName}" to ${server}\n`);

  const folders = fs.readdirSync(bulkDir, { withFileTypes: true })
    .filter((d) => d.isDirectory())
    .map((d) => d.name)
    .sort();

  if (!folders.length) {
    die('No post folders found. Add one folder per post (the folder name becomes the title).');
  }

  let ok = 0;
  let failed = 0;

  for (const folder of folders) {
    const dir = path.join(bulkDir, folder);
    const files = fs.readdirSync(dir);
    const schematic = files.find((f) => f.toLowerCase().endsWith('.litematic'));

    if (!schematic) {
      console.log(`- SKIP  ${folder}  (no .litematic file)`);
      failed++;
      continue;
    }

    const post = readJson(path.join(dir, 'post.json'), {});
    const title = String(post.title || folder).trim();
    const category = String(post.category || defaults.category || 'FARMS').toUpperCase();
    const designer = String(post.designer ?? defaults.designer ?? '').trim();
    const description = String(post.description || '').trim();

    if (!CATEGORIES.has(category)) {
      console.log(`- SKIP  ${folder}  (bad category "${category}")`);
      failed++;
      continue;
    }

    const imageFiles = files
      .filter((f) => IMAGE_EXT[path.extname(f).toLowerCase()])
      .sort()
      .slice(0, 5);

    const form = new FormData();
    form.append('meta', JSON.stringify({ title, category, designer, description }));
    form.append(
      'schematic',
      new Blob([fs.readFileSync(path.join(dir, schematic))], { type: 'application/octet-stream' }),
      schematic,
    );

    if (imageFiles.length) {
      for (const img of imageFiles) {
        const type = IMAGE_EXT[path.extname(img).toLowerCase()];
        form.append('images', new Blob([fs.readFileSync(path.join(dir, img))], { type }), img);
      }
    } else {
      form.append('images', new Blob([placeholderPng()], { type: 'image/png' }), 'placeholder.png');
    }

    try {
      const res = await fetch(server + '/upload', {
        method: 'POST',
        headers: { 'X-Upload-Code': code, 'X-Device-Token': deviceToken },
        body: form,
      });

      if (res.status === 201) {
        console.log(`- OK    ${title}  [${category}]  ${imageFiles.length || 'placeholder'} image(s)`);
        ok++;
      } else {
        const body = await res.json().catch(() => ({}));
        console.log(`- FAIL  ${title}  (${res.status}: ${body.message || 'error'})`);
        failed++;
      }
    } catch (e) {
      console.log(`- FAIL  ${title}  (${e.message})`);
      failed++;
    }
  }

  console.log(`\nDone. ${ok} uploaded, ${failed} skipped/failed.`);
}

main();
