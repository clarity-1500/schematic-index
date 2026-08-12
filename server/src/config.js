import 'dotenv/config';
import path from 'node:path';

export const config = {
  port: Number(process.env.PORT) || 8080,
  dataDir: path.resolve(process.env.DATA_DIR || 'data'),
  ownerKey: process.env.OWNER_KEY || '',

  publicBase: (process.env.PUBLIC_BASE || 'http://localhost:8080').replace(/\/+$/, ''),
  discordWebhook: process.env.DISCORD_WEBHOOK || '',
};

export const paths = {
  db: path.join(config.dataDir, 'index.db'),
  files: path.join(config.dataDir, 'files'),
  images: path.join(config.dataDir, 'files', 'img'),
  schematics: path.join(config.dataDir, 'files', 'sch'),
};

export function fileUrl(key) {
  return key ? `${config.publicBase}/files/${key}` : null;
}
