import { db } from './db.js';
import { rateLimit } from './ratelimit.js';
import { reportToDiscord } from './discord.js';

// Report reasons the mod can send. Kept in lockstep with the mod's report picker.
const REASONS = new Set(['NSFW', 'STOLEN', 'SPAM', 'OTHER']);

const visiblePost = db.prepare("SELECT * FROM posts WHERE id = ? AND visibility = 'visible'");
const likeCount = db.prepare('SELECT likes FROM posts WHERE id = ?');
const addLike = db.prepare('INSERT OR IGNORE INTO likes (post_id, token, created_at) VALUES (?, ?, ?)');
const dropLike = db.prepare('DELETE FROM likes WHERE post_id = ? AND token = ?');
const bumpLikes = db.prepare('UPDATE posts SET likes = likes + 1 WHERE id = ?');
const cutLikes = db.prepare('UPDATE posts SET likes = MAX(0, likes - 1) WHERE id = ?');

const downloadCount = db.prepare('SELECT downloads FROM posts WHERE id = ?');
const markDownload = db.prepare('INSERT OR IGNORE INTO downloads (post_id, token, day) VALUES (?, ?, ?)');
const bumpDownloads = db.prepare('UPDATE posts SET downloads = downloads + 1 WHERE id = ?');

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

  // Records a counted download. The file itself is fetched from fileUrl directly; this only tallies.
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

    // One report per (post, token). Re-reporting is a silent no-op.
    if (!existingReport.get(id, req.deviceToken)) {
      addReport.run(id, reason, note, req.deviceToken, Date.now());
      bumpReports.run(id);
      reportToDiscord({ post, reason, note, count: post.report_count + 1 });
    }

    res.json({ ok: true });
  });
}
