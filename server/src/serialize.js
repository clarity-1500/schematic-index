import { db } from './db.js';
import { fileUrl } from './config.js';

const imagesStmt = db.prepare('SELECT file_key FROM post_images WHERE post_id = ? ORDER BY position');
const likedStmt = db.prepare('SELECT 1 FROM likes WHERE post_id = ? AND token = ?');

// Trending = a recent surge, not lifetime popularity: only activity in the last
// window counts, so an old post with old likes scores 0 while a fresh burst ranks high.
const TREND_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;
const recentDownloadsStmt = db.prepare('SELECT COUNT(*) n FROM downloads WHERE post_id = ? AND day >= ?');
const recentLikesStmt = db.prepare('SELECT COUNT(*) n FROM likes WHERE post_id = ? AND created_at >= ?');

function trendScore(postId) {
  const since = Date.now() - TREND_WINDOW_MS;
  const sinceDay = new Date(since).toISOString().slice(0, 10);
  const downloads = recentDownloadsStmt.get(postId, sinceDay).n;
  const likes = recentLikesStmt.get(postId, since).n;
  return downloads + likes * 2;
}

export function isLiked(postId, token) {
  return token ? !!likedStmt.get(postId, token) : false;
}

export function serializePost(row, token) {
  const imageUrls = imagesStmt.all(row.id).map((i) => fileUrl(i.file_key)).filter(Boolean);

  return {
    id: row.id,
    title: row.title,
    thumbnailName: row.thumbnail_name || '',
    poster: row.poster,
    designer: row.designer || '',
    category: row.category,
    size: { x: row.size_x, y: row.size_y, z: row.size_z },
    blockCount: row.block_count,
    downloads: row.downloads,
    likes: row.likes,
    postedAt: row.posted_at,
    description: row.description || '',
    thumbnailUrl: fileUrl(row.thumbnail_key),
    imageUrls,
    fileUrl: fileUrl(row.file_key),
    fileHash: row.file_hash,
    fileSize: row.file_size,
    liked: isLiked(row.id, token),
    trendScore: trendScore(row.id),
  };
}

export function serializeNews(row) {
  return {
    badge: row.badge,
    title: row.title,
    when: row.date_text,
    lines: row.body.split('\n\n').map((s) => s.trim()).filter(Boolean),
    highlight: !!row.highlight,
  };
}
