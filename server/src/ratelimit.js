// A small fixed-window rate limiter keyed by device token. In-memory, which is fine for a
// single-instance deployment; if this ever scales out, swap the map for a shared store.
const buckets = new Map();

/**
 * Middleware that requires an X-Device-Token and caps requests per token per window. Each limiter
 * gets its own `name` so, say, likes and reports are counted separately.
 */
export function rateLimit({ name, windowMs, max }) {
  return (req, res, next) => {
    const token = (req.get('X-Device-Token') || '').trim();

    if (!token) {
      return res.status(400).json({ error: 'missing_token', message: 'A device token is required.' });
    }

    req.deviceToken = token;

    const id = `${name}:${token}`;
    const now = Date.now();
    let bucket = buckets.get(id);

    if (!bucket || bucket.resetAt <= now) {
      bucket = { count: 0, resetAt: now + windowMs };
      buckets.set(id, bucket);
    }

    bucket.count += 1;

    if (bucket.count > max) {
      res.set('Retry-After', String(Math.ceil((bucket.resetAt - now) / 1000)));
      return res.status(429).json({ error: 'rate_limited', message: 'Too many requests. Slow down.' });
    }

    next();
  };
}

// Sweep expired buckets so the map cannot grow without bound.
setInterval(() => {
  const now = Date.now();
  for (const [key, bucket] of buckets) {
    if (bucket.resetAt <= now) {
      buckets.delete(key);
    }
  }
}, 60_000).unref();
