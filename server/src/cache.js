// Small in-memory response cache for the hot read paths. Entries expire on a short
// TTL and are dropped wholesale when posts change, so thousands of concurrent reads
// are served from memory instead of hammering SQLite on every request.

const store = new Map();
const MAX_ENTRIES = 500;

export function cached(key, ttlMs, build) {
  const now = Date.now();
  const hit = store.get(key);

  if (hit && now < hit.exp) {
    return hit.value;
  }

  const value = build();
  store.set(key, { value, exp: now + ttlMs });

  if (store.size > MAX_ENTRIES) {
    store.delete(store.keys().next().value);
  }

  return value;
}

export function invalidatePosts() {
  for (const key of [...store.keys()]) {
    if (key.startsWith('index:') || key.startsWith('news:')) {
      store.delete(key);
    }
  }
}
