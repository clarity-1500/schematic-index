import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import { paths } from './config.js';

fs.mkdirSync(paths.images, { recursive: true });
fs.mkdirSync(paths.schematics, { recursive: true });

export const db = new DatabaseSync(paths.db);
db.exec('PRAGMA journal_mode = WAL');
db.exec('PRAGMA synchronous = NORMAL');
db.exec('PRAGMA busy_timeout = 5000');
db.exec('PRAGMA cache_size = -16000');
db.exec('PRAGMA temp_store = MEMORY');
db.exec('PRAGMA mmap_size = 268435456');
db.exec('PRAGMA foreign_keys = ON');

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

  -- Server-editable credits list shown in the mod's Settings panel.
  CREATE TABLE IF NOT EXISTS credits (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    username    TEXT NOT NULL,
    nickname    TEXT,
    role        TEXT,
    description TEXT,
    position    INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL
  );

  -- Key/value store for singleton editable content (announcement, terms).
  CREATE TABLE IF NOT EXISTS content_kv (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
  );

  -- Server-editable link buttons rendered as icons in the mod's left rail.
  CREATE TABLE IF NOT EXISTS links (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    icon_key   TEXT,
    url        TEXT NOT NULL,
    label      TEXT,
    position   INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
  );

  -- Server-editable partners rendered as an icon + name rail on the browse page.
  CREATE TABLE IF NOT EXISTS partners (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    icon_key   TEXT,
    url        TEXT NOT NULL,
    name       TEXT,
    position   INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
  );

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

  -- One counted view per (post, token, day): unique daily viewers of a post.
  CREATE TABLE IF NOT EXISTS views (
    post_id  TEXT NOT NULL,
    token    TEXT NOT NULL,
    day      TEXT NOT NULL,
    PRIMARY KEY (post_id, token, day)
  );

  -- One row per (follower, poster) pair, tagged with the post that drove the follow.
  CREATE TABLE IF NOT EXISTS follows (
    follower_token TEXT NOT NULL,
    poster         TEXT NOT NULL,
    post_id        TEXT,
    created_at     INTEGER NOT NULL,
    PRIMARY KEY (follower_token, poster)
  );
  CREATE INDEX IF NOT EXISTS idx_follows_poster ON follows(poster, created_at);
  CREATE INDEX IF NOT EXISTS idx_follows_post ON follows(post_id, created_at);

  -- Indexes matching the hot query patterns: the per-token liked overlay on /index,
  -- and the day/created_at rollups behind the analytics endpoints.
  CREATE INDEX IF NOT EXISTS idx_likes_token ON likes(token);
  CREATE INDEX IF NOT EXISTS idx_likes_created ON likes(created_at);
  CREATE INDEX IF NOT EXISTS idx_downloads_day ON downloads(day);
  CREATE INDEX IF NOT EXISTS idx_downloads_post ON downloads(post_id, day);
  CREATE INDEX IF NOT EXISTS idx_views_day ON views(day);
  CREATE INDEX IF NOT EXISTS idx_views_post ON views(post_id, day);
  CREATE INDEX IF NOT EXISTS idx_follows_created ON follows(created_at);
`);

// Migration: add the nickname column to credits tables created before it existed.
const creditColumns = db.prepare("PRAGMA table_info('credits')").all().map((c) => c.name);

if (!creditColumns.includes('nickname')) {
  db.exec('ALTER TABLE credits ADD COLUMN nickname TEXT');
}

// Migration: add the Minecraft IGN column to codes (used for the creator's skin avatar).
const codeColumns = db.prepare("PRAGMA table_info('codes')").all().map((c) => c.name);

if (!codeColumns.includes('ign')) {
  db.exec('ALTER TABLE codes ADD COLUMN ign TEXT');
}

// One star rating per (post, device token). value is in half-stars: 1..10 where 10 = 5.0 stars.
db.exec(`
  CREATE TABLE IF NOT EXISTS stars (
    post_id    TEXT NOT NULL,
    token      TEXT NOT NULL,
    value      INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (post_id, token)
  );
  CREATE INDEX IF NOT EXISTS idx_stars_post ON stars(post_id);
  CREATE INDEX IF NOT EXISTS idx_stars_created ON stars(created_at);

  -- Shared collections: a short code maps to a JSON list of post ids.
  CREATE TABLE IF NOT EXISTS collection_codes (
    code       TEXT PRIMARY KEY,
    post_ids   TEXT NOT NULL,
    created_at INTEGER NOT NULL
  );
`);

console.log(`[db] ready at ${paths.db}`);
