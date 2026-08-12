import 'dotenv/config';
import path from 'node:path';

/** All runtime configuration, read once from the environment. */
export const config = {
  port: Number(process.env.PORT) || 8080,
  dataDir: path.resolve(process.env.DATA_DIR || 'data'),
  ownerKey: process.env.OWNER_KEY || '',
  // Trailing slash stripped so we can join paths without doubling it.
  publicBase: (process.env.PUBLIC_BASE || 'http://localhost:8080').replace(/\/+$/, ''),
  discordWebhook: process.env.DISCORD_WEBHOOK || '',
};

/** Filesystem layout under the data directory. */
export const paths = {
  db: path.join(config.dataDir, 'index.db'),
  files: path.join(config.dataDir, 'files'),
  images: path.join(config.dataDir, 'files', 'img'),
  schematics: path.join(config.dataDir, 'files', 'sch'),
};

/** Builds a public URL for a stored file key like "img/abc.png". */
export function fileUrl(key) {
  return key ? `${config.publicBase}/files/${key}` : null;
}
