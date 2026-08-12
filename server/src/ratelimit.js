const buckets = new Map();

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

setInterval(() => {
  const now = Date.now();
  for (const [key, bucket] of buckets) {
    if (bucket.resetAt <= now) {
      buckets.delete(key);
    }
  }
}, 60_000).unref();
