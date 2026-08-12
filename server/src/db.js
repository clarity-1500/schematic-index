import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import { paths } from './config.js';

// Make sure the data directories exist before opening the database.
fs.mkdirSync(paths.images, { recursive: true });
fs.mkdirSync(paths.schematics, { recursive: true });

// Node's built-in SQLite (Node 22+). No native module to compile, and it works the same on the
// Linux PaaS. The API is synchronous: db.prepare(sql).get()/.all()/.run().
export const db = new DatabaseSync(paths.db);
db.exec('PRAGMA journal_mode = WAL');
db.exec('PRAGMA foreign_keys = ON');

// The full data model. Mirrors docs/backend-contract.md so the read/write endpoints in later
// sections map straight onto these tables. File contents live on disk; only their keys are stored.
db.exec(`
  CREATE TABLE IF NOT EXISTS posts (
    id               TEXT PRIMARY KEY,
    title            TEXT NOT NULL,
    thumbnail_name   TEXT,
    poster           TEXT NOT NULL,
    designer         TEXT,
    category         TEXT NOT NULL,
    size_x           INTEGER NOT NULL DEFAULT 0,
    size_y           INTEGER NOT NULL DEFAULT 0,
    size_z           INTEGER NOT NULL DEFAULT 0,
    block_count      INTEGER NOT NULL DEFAULT 0,
    downloads        INTEGER NOT NULL DEFAULT 0,
    likes            INTEGER NOT NULL DEFAULT 0,
    posted_at        INTEGER NOT NULL,
    description      TEXT,
    thumbnail_key    TEXT,
    file_key         TEXT,
    file_hash        TEXT,
    file_size        INTEGER NOT NULL DEFAULT 0,
    visibility       TEXT NOT NULL DEFAULT 'visible',
    uploader_code_id INTEGER,
    created_token    TEXT,
    created_ip       TEXT,
    report_count     INTEGER NOT NULL DEFAULT 0
  );
  CREATE INDEX IF NOT EXISTS idx_posts_visible ON posts(visibility, posted_at);
  CREATE INDEX IF NOT EXISTS idx_posts_category ON posts(category, visibility);

  CREATE TABLE IF NOT EXISTS post_images (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    post_id   TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    position  INTEGER NOT NULL,
    file_key  TEXT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_post_images ON post_images(post_id, position);

  CREATE TABLE IF NOT EXISTS news (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    badge      TEXT NOT NULL,
    title      TEXT NOT NULL,
    date_text  TEXT NOT NULL,
    body       TEXT NOT NULL,
    highlight  INTEGER NOT NULL DEFAULT 0,
    posted_at  INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_news_posted ON news(posted_at);

  CREATE TABLE IF NOT EXISTS reports (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    post_id         TEXT NOT NULL,
    reason          TEXT NOT NULL,
    note            TEXT,
    reporter_token  TEXT,
    status          TEXT NOT NULL DEFAULT 'open',
    created_at      INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status, created_at);

  CREATE TABLE IF NOT EXISTS codes (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    code         TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    revoked      INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL
  );

  -- One like per (post, device token); the posts.likes counter is the authoritative total.
  CREATE TABLE IF NOT EXISTS likes (
    post_id     TEXT NOT NULL,
    token       TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    PRIMARY KEY (post_id, token)
  );

  -- One counted download per (post, token, day), so refreshes don't inflate the total.
  CREATE TABLE IF NOT EXISTS downloads (
    post_id  TEXT NOT NULL,
    token    TEXT NOT NULL,
    day      TEXT NOT NULL,
    PRIMARY KEY (post_id, token, day)
  );
`);

console.log(`[db] ready at ${paths.db}`);
