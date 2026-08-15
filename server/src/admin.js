import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { db } from './db.js';
import { config } from './config.js';
import { invalidatePosts, invalidateContent } from './cache.js';
import { getCreditsAdmin, getTerms, getLinks, getJsonKv, setKv } from './content.js';

const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');

function requireOwner(req, res, next) {
  if (!config.ownerKey) {
    return res.status(503).json({ error: 'no_owner_key', message: 'Server has no OWNER_KEY set.' });
  }

  const provided = Buffer.from(req.get('X-Owner-Key') || '');
  const expected = Buffer.from(config.ownerKey);

  if (provided.length !== expected.length || !crypto.timingSafeEqual(provided, expected)) {
    return res.status(403).json({ error: 'forbidden', message: 'Bad owner key.' });
  }

  next();
}

export function registerAdminRoutes(app) {
  const owner = requireOwner;

  app.get('/admin', (req, res) => res.sendFile(path.join(publicDir, 'admin.html')));

  app.get('/admin/api/session', owner, (req, res) => res.json({ ok: true }));

  app.get('/admin/api/posts', owner, (req, res) => {
    const rows = db.prepare('SELECT * FROM posts ORDER BY posted_at DESC LIMIT 500').all();
    res.json({
      posts: rows.map((r) => ({
        id: r.id, title: r.title, poster: r.poster, category: r.category,
        likes: r.likes, downloads: r.downloads, visibility: r.visibility,
        reportCount: r.report_count, postedAt: r.posted_at,
      })),
    });
  });

  app.delete('/admin/api/posts/:id', owner, (req, res) => {
    const info = db.prepare("UPDATE posts SET visibility = 'deleted' WHERE id = ?").run(req.params.id);
    invalidatePosts();
    res.json({ ok: true, changed: info.changes });
  });

  app.post('/admin/api/posts/:id/restore', owner, (req, res) => {
    db.prepare("UPDATE posts SET visibility = 'visible' WHERE id = ?").run(req.params.id);
    invalidatePosts();
    res.json({ ok: true });
  });

  app.get('/admin/api/posts/:id/stats', owner, (req, res) => {
    const id = req.params.id;
    const post = db.prepare('SELECT id, title, poster, downloads, likes FROM posts WHERE id = ?').get(id);

    if (!post) {
      return res.status(404).json({ error: 'not_found', message: 'No such post.' });
    }

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
      downloads: labels.map(() => 0),
      views: labels.map(() => 0),
      likes: labels.map(() => 0),
      follows: labels.map(() => 0),
    };

    const fill = (arr, rows) => {
      for (const r of rows) {
        if (r.day in index) arr[index[r.day]] = r.n;
      }
    };

    fill(series.downloads, db.prepare('SELECT day, COUNT(*) n FROM downloads WHERE post_id = ? AND day >= ? GROUP BY day').all(id, since));
    fill(series.views, db.prepare('SELECT day, COUNT(*) n FROM views WHERE post_id = ? AND day >= ? GROUP BY day').all(id, since));
    fill(series.likes, db.prepare("SELECT date(created_at/1000,'unixepoch') day, COUNT(*) n FROM likes WHERE post_id = ? AND created_at >= ? GROUP BY day").all(id, sinceMs));
    fill(series.follows, db.prepare("SELECT date(created_at/1000,'unixepoch') day, COUNT(*) n FROM follows WHERE post_id = ? AND created_at >= ? GROUP BY day").all(id, sinceMs));

    res.json({
      post: { id: post.id, title: post.title, poster: post.poster },
      days: labels,
      series,
      lifetime: {
        downloads: post.downloads,
        likes: post.likes,
        views: db.prepare('SELECT COUNT(*) n FROM views WHERE post_id = ?').get(id).n,
        follows: db.prepare('SELECT COUNT(*) n FROM follows WHERE post_id = ?').get(id).n,
      },
    });
  });

  app.get('/admin/api/stats', owner, (req, res) => {
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
      follows: labels.map(() => 0),
    };

    const fill = (arr, rows) => {
      for (const r of rows) {
        if (r.day in index) arr[index[r.day]] = r.n;
      }
    };

    fill(series.downloads, db.prepare('SELECT day, COUNT(*) n FROM downloads WHERE day >= ? GROUP BY day').all(since));
    fill(series.views, db.prepare('SELECT day, COUNT(*) n FROM views WHERE day >= ? GROUP BY day').all(since));
    fill(series.likes, db.prepare("SELECT date(created_at/1000,'unixepoch') day, COUNT(*) n FROM likes WHERE created_at >= ? GROUP BY day").all(sinceMs));
    fill(series.follows, db.prepare("SELECT date(created_at/1000,'unixepoch') day, COUNT(*) n FROM follows WHERE created_at >= ? GROUP BY day").all(sinceMs));

    res.json({
      totals: {
        posts: db.prepare("SELECT COUNT(*) n FROM posts WHERE visibility = 'visible'").get().n,
        views: db.prepare('SELECT COUNT(*) n FROM views').get().n,
        downloads: db.prepare('SELECT COUNT(*) n FROM downloads').get().n,
        likes: db.prepare('SELECT COUNT(*) n FROM likes').get().n,
        follows: db.prepare('SELECT COUNT(*) n FROM follows').get().n,
      },
      days: labels,
      series,
      top: {
        views: db.prepare('SELECT p.id, p.title, p.poster, COUNT(v.post_id) n FROM posts p JOIN views v ON v.post_id = p.id GROUP BY p.id ORDER BY n DESC LIMIT 6').all(),
        downloads: db.prepare('SELECT id, title, poster, downloads n FROM posts WHERE downloads > 0 ORDER BY downloads DESC LIMIT 6').all(),
      },
    });
  });

  app.get('/admin/api/news', owner, (req, res) => {
    const rows = db.prepare('SELECT * FROM news ORDER BY posted_at DESC').all();
    res.json({
      news: rows.map((r) => ({
        id: r.id, badge: r.badge, title: r.title, dateText: r.date_text,
        body: r.body, highlight: !!r.highlight, postedAt: r.posted_at,
      })),
    });
  });

  app.post('/admin/api/news', owner, (req, res) => {
    const badge = String(req.body?.badge || '').trim();
    const title = String(req.body?.title || '').trim();
    const body = String(req.body?.body || '').trim();
    const dateText = String(req.body?.dateText || '').trim() || new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
    const highlight = req.body?.highlight ? 1 : 0;

    if (!badge || !title || !body) {
      return res.status(400).json({ error: 'missing_fields', message: 'badge, title and body are required.' });
    }

    if (highlight) {
      db.prepare('UPDATE news SET highlight = 0').run();
    }

    const info = db.prepare(
      'INSERT INTO news (badge, title, date_text, body, highlight, posted_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).run(badge, title, dateText, body, highlight, Date.now());

    invalidatePosts();
    res.status(201).json({ ok: true, id: Number(info.lastInsertRowid) });
  });

  app.delete('/admin/api/news/:id', owner, (req, res) => {
    db.prepare('DELETE FROM news WHERE id = ?').run(Number(req.params.id));
    invalidatePosts();
    res.json({ ok: true });
  });

  app.get('/admin/api/reports', owner, (req, res) => {
    const rows = db.prepare(`
      SELECT r.*, p.title AS post_title, p.poster AS post_poster, p.uploader_code_id AS code_id
      FROM reports r LEFT JOIN posts p ON p.id = r.post_id
      WHERE r.status = 'open' ORDER BY r.created_at DESC LIMIT 200
    `).all();
    res.json({
      reports: rows.map((r) => ({
        id: r.id, postId: r.post_id, postTitle: r.post_title, poster: r.post_poster,
        reason: r.reason, note: r.note, createdAt: r.created_at, codeId: r.code_id,
      })),
    });
  });

  app.post('/admin/api/reports/:id/dismiss', owner, (req, res) => {
    db.prepare("UPDATE reports SET status = 'dismissed' WHERE id = ?").run(Number(req.params.id));
    res.json({ ok: true });
  });

  app.get('/admin/api/codes', owner, (req, res) => {
    const rows = db.prepare('SELECT * FROM codes ORDER BY created_at DESC').all();
    res.json({
      codes: rows.map((r) => ({
        id: r.id, code: r.code, displayName: r.display_name, revoked: !!r.revoked, createdAt: r.created_at,
      })),
    });
  });

  app.post('/admin/api/codes', owner, (req, res) => {
    const displayName = String(req.body?.displayName || '').trim();

    if (!displayName) {
      return res.status(400).json({ error: 'no_name', message: 'A display name is required.' });
    }

    const code = crypto.randomBytes(6).toString('hex').toUpperCase();
    db.prepare('INSERT INTO codes (code, display_name, revoked, created_at) VALUES (?, ?, 0, ?)')
      .run(code, displayName, Date.now());
    res.status(201).json({ ok: true, code, displayName });
  });

  app.post('/admin/api/codes/:id/update', owner, (req, res) => {
    const id = Number(req.params.id);
    const row = db.prepare('SELECT * FROM codes WHERE id = ?').get(id);

    if (!row) {
      return res.status(404).json({ error: 'not_found', message: 'No such code.' });
    }

    const newCode = String(req.body?.code ?? row.code).trim().toUpperCase();
    const displayName = String(req.body?.displayName ?? row.display_name).trim();

    if (!/^[A-Z0-9-]+$/.test(newCode)) {
      return res.status(400).json({ error: 'bad_code', message: 'Code must be letters, numbers, or dashes.' });
    }

    if (!displayName) {
      return res.status(400).json({ error: 'no_name', message: 'A display name is required.' });
    }

    const clash = db.prepare('SELECT id FROM codes WHERE code = ? AND id != ?').get(newCode, id);

    if (clash) {
      return res.status(409).json({ error: 'code_taken', message: 'That code is already in use.' });
    }

    db.prepare('UPDATE codes SET code = ?, display_name = ? WHERE id = ?').run(newCode, displayName, id);
    res.json({ ok: true, code: newCode, displayName });
  });

  app.post('/admin/api/codes/:code/revoke', owner, (req, res) => {
    const code = req.params.code;
    db.prepare('UPDATE codes SET revoked = 1 WHERE code = ?').run(code);

    if (req.body?.deletePosts) {
      const row = db.prepare('SELECT id FROM codes WHERE code = ?').get(code);
      if (row) {
        db.prepare("UPDATE posts SET visibility = 'deleted' WHERE uploader_code_id = ?").run(row.id);
        invalidatePosts();
      }
    }

    res.json({ ok: true });
  });

  app.get('/admin/api/content', owner, (req, res) => {
    res.json({
      credits: getCreditsAdmin(),
      announcement: getJsonKv('announcement', null),
      terms: getTerms(),
      links: getLinks(),
    });
  });

  app.post('/admin/api/credits', owner, (req, res) => {
    const username = String(req.body?.username || '').trim();
    const role = String(req.body?.role || '').trim();
    const description = String(req.body?.description || '').trim();

    if (!username) {
      return res.status(400).json({ error: 'no_username', message: 'A Minecraft username is required.' });
    }

    const position = db.prepare('SELECT COALESCE(MAX(position), -1) + 1 AS p FROM credits').get().p;
    const info = db.prepare(
      'INSERT INTO credits (username, role, description, position, created_at) VALUES (?, ?, ?, ?, ?)',
    ).run(username, role, description, position, Date.now());

    invalidateContent();
    res.status(201).json({ ok: true, id: Number(info.lastInsertRowid) });
  });

  app.post('/admin/api/credits/:id', owner, (req, res) => {
    const id = Number(req.params.id);
    const row = db.prepare('SELECT * FROM credits WHERE id = ?').get(id);

    if (!row) {
      return res.status(404).json({ error: 'not_found', message: 'No such credit.' });
    }

    const username = String(req.body?.username ?? row.username).trim();
    const role = String(req.body?.role ?? row.role ?? '').trim();
    const description = String(req.body?.description ?? row.description ?? '').trim();

    if (!username) {
      return res.status(400).json({ error: 'no_username', message: 'A Minecraft username is required.' });
    }

    db.prepare('UPDATE credits SET username = ?, role = ?, description = ? WHERE id = ?')
      .run(username, role, description, id);
    invalidateContent();
    res.json({ ok: true });
  });

  app.delete('/admin/api/credits/:id', owner, (req, res) => {
    db.prepare('DELETE FROM credits WHERE id = ?').run(Number(req.params.id));
    invalidateContent();
    res.json({ ok: true });
  });

  app.put('/admin/api/announcement', owner, (req, res) => {
    const title = String(req.body?.title || '').trim();
    const tag = String(req.body?.tag || '').trim();
    const description = String(req.body?.description || '').trim();
    const active = req.body?.active !== false && !!title;

    if (active) {
      setKv('announcement', JSON.stringify({ id: String(Date.now()), title, tag, description, active: true }));
    } else {
      setKv('announcement', JSON.stringify({ active: false }));
    }

    invalidateContent();
    res.json({ ok: true, active });
  });

  app.put('/admin/api/terms', owner, (req, res) => {
    const body = String(req.body?.body || '').trim();

    if (!body) {
      return res.status(400).json({ error: 'no_body', message: 'Terms body is required.' });
    }

    const version = Number(req.body?.version) || (getTerms().version + 1);
    setKv('terms', JSON.stringify({ version, body }));
    invalidateContent();
    res.json({ ok: true, version });
  });

  app.put('/admin/api/links', owner, (req, res) => {
    const links = {
      discord: String(req.body?.discord || '').trim(),
      modrinth: String(req.body?.modrinth || '').trim(),
      support: String(req.body?.support || '').trim(),
    };
    setKv('links', JSON.stringify(links));
    invalidateContent();
    res.json({ ok: true });
  });
}
