import crypto from 'node:crypto';
import { db } from './db.js';
import { rateLimit } from './ratelimit.js';
import { reportToDiscord } from './discord.js';

const REASONS = new Set(['NSFW', 'STOLEN', 'SPAM', 'OTHER']);

// Unambiguous alphabet (no 0/O/1/I/L) for 5-char shared-collection codes.
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

function newCode() {
  let code = '';
  for (let i = 0; i < 5; i++) code += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
  return code;
}

const visibleForShare = db.prepare("SELECT 1 FROM posts WHERE id = ? AND visibility = 'visible'");
const insertCollectionCode = db.prepare('INSERT INTO collection_codes (code, post_ids, created_at) VALUES (?, ?, ?)');
const getCollectionCode = db.prepare('SELECT post_ids FROM collection_codes WHERE code = ?');

const visiblePost = db.prepare("SELECT * FROM posts WHERE id = ? AND visibility = 'visible'");
const likeCount = db.prepare('SELECT likes FROM posts WHERE id = ?');
const addLike = db.prepare('INSERT OR IGNORE INTO likes (post_id, token, created_at) VALUES (?, ?, ?)');
const dropLike = db.prepare('DELETE FROM likes WHERE post_id = ? AND token = ?');
const bumpLikes = db.prepare('UPDATE posts SET likes = likes + 1 WHERE id = ?');
const cutLikes = db.prepare('UPDATE posts SET likes = MAX(0, likes - 1) WHERE id = ?');

const downloadCount = db.prepare('SELECT downloads FROM posts WHERE id = ?');
const markDownload = db.prepare('INSERT OR IGNORE INTO downloads (post_id, token, day) VALUES (?, ?, ?)');
const bumpDownloads = db.prepare('UPDATE posts SET downloads = downloads + 1 WHERE id = ?');

const markView = db.prepare('INSERT OR IGNORE INTO views (post_id, token, day) VALUES (?, ?, ?)');

const addFollow = db.prepare(`
  INSERT INTO follows (follower_token, poster, post_id, created_at) VALUES (?, ?, ?, ?)
  ON CONFLICT(follower_token, poster) DO UPDATE SET post_id = excluded.post_id, created_at = excluded.created_at
`);
const dropFollow = db.prepare('DELETE FROM follows WHERE follower_token = ? AND poster = ?');

const setStar = db.prepare(`
  INSERT INTO stars (post_id, token, value, created_at) VALUES (?, ?, ?, ?)
  ON CONFLICT(post_id, token) DO UPDATE SET value = excluded.value, created_at = excluded.created_at
`);
const dropStar = db.prepare('DELETE FROM stars WHERE post_id = ? AND token = ?');
const starSummary = db.prepare('SELECT COUNT(*) n, COALESCE(AVG(value), 0) avg FROM stars WHERE post_id = ?');

const existingReport = db.prepare('SELECT 1 FROM reports WHERE post_id = ? AND reporter_token = ?');
const addReport = db.prepare(
  "INSERT INTO reports (post_id, reason, note, reporter_token, status, created_at) VALUES (?, ?, ?, ?, 'open', ?)",
);
const bumpReports = db.prepare('UPDATE posts SET report_count = report_count + 1 WHERE id = ?');

function postId(req) {
  return String(req.body?.postId || '').trim();
}

export function registerInteractionRoutes(app) {
  const likeLimit = rateLimit({ name: 'like', windowMs: 60_000, max: 60 });
  const downloadLimit = rateLimit({ name: 'download', windowMs: 60_000, max: 30 });
  const reportLimit = rateLimit({ name: 'report', windowMs: 3_600_000, max: 5 });
  const viewLimit = rateLimit({ name: 'view', windowMs: 60_000, max: 120 });
  const followLimit = rateLimit({ name: 'follow', windowMs: 60_000, max: 60 });

  app.post('/like', likeLimit, (req, res) => {
    const id = postId(req);

    if (!visiblePost.get(id)) {
      return res.status(404).json({ error: 'not_found', message: 'No such post.' });
    }

    if (addLike.run(id, req.deviceToken, Date.now()).changes > 0) {
      bumpLikes.run(id);
    }

    res.json({ likes: likeCount.get(id).likes, liked: true });
  });

  app.post('/unlike', likeLimit, (req, res) => {
    const id = postId(req);

    if (!visiblePost.get(id)) {
      return res.status(404).json({ error: 'not_found', message: 'No such post.' });
    }

    if (dropLike.run(id, req.deviceToken).changes > 0) {
      cutLikes.run(id);
    }

    res.json({ likes: likeCount.get(id).likes, liked: false });
  });

  app.post('/download', downloadLimit, (req, res) => {
    const id = postId(req);

    if (!visiblePost.get(id)) {
      return res.status(404).json({ error: 'not_found', message: 'No such post.' });
    }

    const day = new Date().toISOString().slice(0, 10);

    if (markDownload.run(id, req.deviceToken, day).changes > 0) {
      bumpDownloads.run(id);
    }

    res.json({ downloads: downloadCount.get(id).downloads });
  });

  app.post('/view', viewLimit, (req, res) => {
    const id = postId(req);

    if (!visiblePost.get(id)) {
      return res.status(404).json({ error: 'not_found', message: 'No such post.' });
    }

    const day = new Date().toISOString().slice(0, 10);
    markView.run(id, req.deviceToken, day);
    res.json({ ok: true });
  });

  app.post('/follow', followLimit, (req, res) => {
    const poster = String(req.body?.poster || '').trim();

    if (!poster) {
      return res.status(400).json({ error: 'bad_poster', message: 'Missing poster.' });
    }

    addFollow.run(req.deviceToken, poster, postId(req) || null, Date.now());
    res.json({ ok: true });
  });

  app.post('/unfollow', followLimit, (req, res) => {
    const poster = String(req.body?.poster || '').trim();

    if (!poster) {
      return res.status(400).json({ error: 'bad_poster', message: 'Missing poster.' });
    }

    dropFollow.run(req.deviceToken, poster);
    res.json({ ok: true });
  });

  const rateLimiter = rateLimit({ name: 'rate', windowMs: 60_000, max: 60 });

  app.post('/rate', rateLimiter, (req, res) => {
    const id = postId(req);

    if (!visiblePost.get(id)) {
      return res.status(404).json({ error: 'not_found', message: 'No such post.' });
    }

    // value is in half-stars: 1..10 (10 = 5.0). 0 clears the rating.
    const value = Math.max(0, Math.min(10, Math.round(Number(req.body?.value) || 0)));

    if (value === 0) {
      dropStar.run(id, req.deviceToken);
    } else {
      setStar.run(id, req.deviceToken, value, Date.now());
    }

    const summary = starSummary.get(id);
    res.json({ starCount: summary.n, starAvg: summary.avg / 2, myStars: value });
  });

  const shareLimit = rateLimit({ name: 'share', windowMs: 60_000, max: 20 });

  app.post('/collections/share', shareLimit, (req, res) => {
    const raw = Array.isArray(req.body?.postIds) ? req.body.postIds.map(String) : [];
    const valid = [...new Set(raw)].filter((id) => id && visibleForShare.get(id)).slice(0, 200);

    if (!valid.length) {
      return res.status(400).json({ error: 'empty', message: 'None of those posts are available to share.' });
    }

    for (let attempt = 0; attempt < 8; attempt++) {
      const code = newCode();

      try {
        insertCollectionCode.run(code, JSON.stringify(valid), Date.now());
        return res.json({ code });
      } catch {
        // Code collision - try another.
      }
    }

    res.status(500).json({ error: 'code_failed', message: 'Could not generate a code, try again.' });
  });

  app.get('/collections/:code', (req, res) => {
    const row = getCollectionCode.get(String(req.params.code || '').toUpperCase());

    if (!row) {
      return res.status(404).json({ error: 'not_found', message: 'Unknown collection code.' });
    }

    let postIds = [];
    try {
      postIds = JSON.parse(row.post_ids);
    } catch {
      postIds = [];
    }

    res.json({ postIds });
  });

  app.post('/report', reportLimit, (req, res) => {
    const id = postId(req);
    const reason = String(req.body?.reason || '').toUpperCase();
    const note = String(req.body?.note || '').slice(0, 500);

    if (!REASONS.has(reason)) {
      return res.status(400).json({ error: 'bad_reason', message: 'Unknown report reason.' });
    }

    const post = visiblePost.get(id);

    if (!post) {
      return res.status(404).json({ error: 'not_found', message: 'No such post.' });
    }

    if (!existingReport.get(id, req.deviceToken)) {
      addReport.run(id, reason, note, req.deviceToken, Date.now());
      bumpReports.run(id);
      reportToDiscord({ post, reason, note, count: post.report_count + 1 });
    }

    res.json({ ok: true });
  });
}
