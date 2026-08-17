import express from 'express';
import { db } from './db.js';
import { paths } from './config.js';
import { serializePost, serializeNews } from './serialize.js';
import { cached } from './cache.js';
import { buildContent } from './content.js';

const INDEX_TTL_MS = 15_000;
const NEWS_TTL_MS = 30_000;
const CONTENT_TTL_MS = 30_000;
const likedByToken = db.prepare('SELECT post_id FROM likes WHERE token = ?');

const CATEGORIES = new Set([
  'FARMS', 'CONTRAPTIONS', 'REGEARS', 'STASHES', 'GAMBLING_BASES', 'HANGOUT_BASES', 'MEGA_BUILDS',
]);

const SORTS = {
  newest: 'posted_at DESC, id DESC',
  downloads: 'downloads DESC, id DESC',
  likes: 'likes DESC, id DESC',
};

function decodeCursor(cursor) {
  if (!cursor) return 0;
  try {
    const parsed = JSON.parse(Buffer.from(String(cursor), 'base64').toString('utf8'));
    return Math.max(0, Number(parsed.offset) || 0);
  } catch {
    return 0;
  }
}

function encodeCursor(offset) {
  return Buffer.from(JSON.stringify({ offset })).toString('base64');
}

export function registerReadRoutes(app) {
  app.use('/files', express.static(paths.files, { maxAge: '365d', immutable: true }));

  app.get('/index', (req, res) => {
    const token = req.get('X-Device-Token') || '';
    const category = String(req.query.category || '').toUpperCase();
    const sortKey = String(req.query.sort || 'newest');
    const sort = SORTS[sortKey] || SORTS.newest;
    const search = String(req.query.search || '').trim();
    const limit = Math.min(60, Math.max(1, Number(req.query.limit) || 24));
    const offset = decodeCursor(req.query.cursor);
    const cat = CATEGORIES.has(category) ? category : '';

    const cacheKey = `index:${cat}|${sortKey}|${search.toLowerCase()}|${limit}|${offset}`;

    const base = cached(cacheKey, INDEX_TTL_MS, () => {
      const where = ["visibility = 'visible'"];
      const args = [];

      if (cat) {
        where.push('category = ?');
        args.push(cat);
      }

      if (search) {
        where.push('(title LIKE ? OR poster LIKE ? OR designer LIKE ?)');
        const like = `%${search}%`;
        args.push(like, like, like);
      }

      const whereSql = where.join(' AND ');
      const total = db.prepare(`SELECT COUNT(*) AS n FROM posts WHERE ${whereSql}`).get(...args).n;
      const rows = db
        .prepare(`SELECT * FROM posts WHERE ${whereSql} ORDER BY ${sort} LIMIT ? OFFSET ?`)
        .all(...args, limit, offset);

      const posts = rows.map((r) => serializePost(r, ''));
      const nextCursor = offset + rows.length < total ? encodeCursor(offset + limit) : null;
      return { posts, nextCursor, total };
    });

    let posts = base.posts;

    if (token) {
      const liked = new Set(likedByToken.all(token).map((r) => r.post_id));

      if (liked.size) {
        posts = base.posts.map((p) => (liked.has(p.id) ? { ...p, liked: true } : p));
      }
    }

    res.json({ posts, nextCursor: base.nextCursor, total: base.total });
  });

  app.get('/post/:id', (req, res) => {
    const token = req.get('X-Device-Token') || '';
    const row = db.prepare("SELECT * FROM posts WHERE id = ? AND visibility = 'visible'").get(req.params.id);

    if (!row) {
      return res.status(404).json({ error: 'not_found', message: 'No such post.' });
    }

    res.json(serializePost(row, token));
  });

  app.get('/news', (req, res) => {
    const news = cached('news:all', NEWS_TTL_MS, () =>
      db.prepare('SELECT * FROM news ORDER BY posted_at DESC, id DESC').all().map(serializeNews));
    res.json({ news });
  });

  app.get('/content', (req, res) => {
    const content = cached('content:all', CONTENT_TTL_MS, () => buildContent());
    res.json(content);
  });

  app.get('/creator/:poster', (req, res) => {
    const poster = String(req.params.poster || '');
    const code = db.prepare('SELECT ign FROM codes WHERE display_name = ? ORDER BY id DESC').get(poster);
    const followers = db.prepare('SELECT COUNT(*) AS n FROM follows WHERE poster = ?').get(poster).n;
    const agg = db.prepare(
      "SELECT COUNT(*) AS posts, COALESCE(SUM(downloads), 0) AS downloads, COALESCE(SUM(likes), 0) AS likes "
      + "FROM posts WHERE poster = ? AND visibility = 'visible'",
    ).get(poster);
    res.json({
      poster,
      ign: code?.ign || '',
      followers,
      posts: agg.posts,
      downloads: agg.downloads,
      likes: agg.likes,
    });
  });
}
