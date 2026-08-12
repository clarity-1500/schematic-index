import { db } from './db.js';
import { fileUrl } from './config.js';

const imagesStmt = db.prepare('SELECT file_key FROM post_images WHERE post_id = ? ORDER BY position');
const likedStmt = db.prepare('SELECT 1 FROM likes WHERE post_id = ? AND token = ?');

/** Whether a device token has liked a post. */
export function isLiked(postId, token) {
  return token ? !!likedStmt.get(postId, token) : false;
}

/** Turns a posts row into the JSON the mod consumes. Shapes match docs/backend-contract.md. */
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
  };
}

/** Turns a news row into the JSON the mod's News tab consumes. Body paragraphs split on blank lines. */
export function serializeNews(row) {
  return {
    badge: row.badge,
    title: row.title,
    when: row.date_text,
    lines: row.body.split('\n\n').map((s) => s.trim()).filter(Boolean),
    highlight: !!row.highlight,
  };
}
