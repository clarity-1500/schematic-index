import crypto from 'node:crypto';
import { db } from './db.js';
import { rateLimit } from './ratelimit.js';

// We store only a hash of the anonymous device token, never the token itself, so a row can't be
// tied back to a person even with database access.
function hashToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

const upsertSeen = db.prepare(`
  INSERT INTO seen_installs (token_hash, first_seen, last_seen, version)
  VALUES (?, ?, ?, ?)
  ON CONFLICT(token_hash) DO UPDATE SET last_seen = excluded.last_seen, version = excluded.version
`);

const totalInstalls = db.prepare('SELECT COUNT(*) n FROM seen_installs');
const activeSince = db.prepare('SELECT COUNT(*) n FROM seen_installs WHERE last_seen >= ?');

// Clients heartbeat every 10 minutes; count an install "online" for 15 (interval + grace) so a
// still-running client is never briefly dropped in the gap between two beats.
export const ONLINE_WINDOW_MS = 15 * 60 * 1000;

const DAY_MS = 86_400_000;

export function usageSnapshot() {
  const now = Date.now();
  return {
    total: totalInstalls.get().n,
    online: activeSince.get(now - ONLINE_WINDOW_MS).n,
    day: activeSince.get(now - DAY_MS).n,
    week: activeSince.get(now - 7 * DAY_MS).n,
    month: activeSince.get(now - 30 * DAY_MS).n,
  };
}

export function registerPresenceRoutes(app) {
  // Heartbeats arrive ~every 10 min; 10/min per token is ample headroom and stops abuse.
  const presenceLimit = rateLimit({ name: 'presence', windowMs: 60_000, max: 10 });

  app.post('/presence', presenceLimit, (req, res) => {
    const now = Date.now();
    const version = String(req.body?.version || '').trim().slice(0, 32) || null;
    upsertSeen.run(hashToken(req.deviceToken), now, now, version);
    res.status(204).end();
  });
}
