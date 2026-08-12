// Dev test for the upload flow: mints a code, builds a tiny valid .litematic (gzipped NBT with
// real dimensions), posts it multipart, and prints the result. Run with the server up.
import zlib from 'node:zlib';
import nbt from 'prismarine-nbt';
import { db } from '../src/db.js';

db.prepare('INSERT OR IGNORE INTO codes (code, display_name, revoked, created_at) VALUES (?, ?, 0, ?)')
  .run('TESTCODE', 'TestUploader', Date.now());

const root = {
  type: 'compound',
  name: '',
  value: {
    Metadata: {
      type: 'compound',
      value: {
        EnclosingSize: {
          type: 'compound',
          value: {
            x: { type: 'int', value: 17 },
            y: { type: 'int', value: 6 },
            z: { type: 'int', value: 23 },
          },
        },
        TotalBlocks: { type: 'int', value: 842 },
        Name: { type: 'string', value: 'Test Build' },
      },
    },
  },
};

const gz = zlib.gzipSync(nbt.writeUncompressed(root, 'big'));

const form = new FormData();
form.append('meta', JSON.stringify({
  title: 'Uploaded Gold Farm - full drop', thumbnailName: 'Gold Farm',
  designer: 'Tester', category: 'FARMS', description: 'Posted through the upload endpoint.',
}));
form.append('schematic', new Blob([gz]), 'build.litematic');
form.append('images', new Blob([Buffer.from('fake-png-bytes')], { type: 'image/png' }), 'shot0.png');
form.append('images', new Blob([Buffer.from('fake-png-bytes-2')], { type: 'image/png' }), 'shot1.png');

const res = await fetch('http://localhost:8080/upload', {
  method: 'POST',
  headers: { 'X-Upload-Code': 'TESTCODE' },
  body: form,
});

const body = await res.json();
console.log('status', res.status);
console.log('id', body.id, '| size', JSON.stringify(body.size), '| blocks', body.blockCount);
console.log('poster', body.poster, '| images', body.imageUrls?.length, '| fileUrl', body.fileUrl);
console.log('hash', body.fileHash?.slice(0, 22) + '...');
