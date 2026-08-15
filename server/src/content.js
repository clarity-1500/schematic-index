import { db } from './db.js';
import { fileUrl } from './config.js';

// Server-editable content the mod pulls at runtime, so credits, the announcement
// banner, the terms text and outbound links can change without shipping a new jar.

const DEFAULT_TERMS_VERSION = 1;
const DEFAULT_TERMS_BODY =
  'By using The Schematic Index, you agree to these terms. If you do not agree, you cannot use '
  + 'the online service.\n\n'
  + 'Data & Privacy: The Service connects to external servers to sync the online catalog. '
  + 'It transmits a random local identifier along with your interactions (likes, downloads, '
  + 'and reports). You can revoke consent anytime in Settings, which disables online '
  + 'connectivity.\n\n'
  + 'Content Ownership: Do not upload content you do not have the legal rights to '
  + 'distribute. By uploading, you grant us a license to host and share your schematic. We '
  + 'reserve the right to remove infringing content and terminate access for abuse.\n\n'
  + 'Disclaimer: This service is provided "as-is." We are not liable for server downtime '
  + 'or issues caused by third-party files.';

const getKvStmt = db.prepare('SELECT value FROM content_kv WHERE key = ?');
const setKvStmt = db.prepare(
  'INSERT INTO content_kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value',
);

export function getKv(key) {
  const row = getKvStmt.get(key);
  return row ? row.value : null;
}

export function setKv(key, value) {
  setKvStmt.run(key, value);
}

export function getJsonKv(key, fallback) {
  const raw = getKv(key);

  if (!raw) {
    return fallback;
  }

  try {
    return JSON.parse(raw);
  } catch {
    return fallback;
  }
}

export function avatarUrl(username) {
  return `https://mc-heads.net/avatar/${encodeURIComponent(username)}/64.png`;
}

export function getTerms() {
  const terms = getJsonKv('terms', null);

  return {
    version: Number(terms?.version) || DEFAULT_TERMS_VERSION,
    body: String(terms?.body || DEFAULT_TERMS_BODY),
  };
}

export function getLinksAdmin() {
  return db.prepare('SELECT * FROM links ORDER BY position ASC, id ASC').all().map((r) => ({
    id: r.id,
    url: r.url,
    label: r.label || '',
    iconUrl: fileUrl(r.icon_key),
  }));
}

export function getLinks() {
  return getLinksAdmin().map((l) => ({ url: l.url, label: l.label, iconUrl: l.iconUrl }));
}

export function getCreditsAdmin() {
  return db.prepare('SELECT * FROM credits ORDER BY position ASC, id ASC').all().map((r) => ({
    id: r.id,
    username: r.username,
    nickname: r.nickname || '',
    role: r.role || '',
    description: r.description || '',
  }));
}

export function getAnnouncement() {
  const a = getJsonKv('announcement', null);

  if (!a || a.active === false || !a.title) {
    return null;
  }

  return {
    id: String(a.id || ''),
    title: String(a.title || ''),
    tag: String(a.tag || ''),
    description: String(a.description || ''),
    color: String(a.color || ''),
  };
}

// The public document the mod fetches from GET /content.
export function buildContent() {
  return {
    credits: getCreditsAdmin().map((c) => ({
      username: c.username,
      nickname: c.nickname,
      role: c.role,
      description: c.description,
      avatarUrl: avatarUrl(c.username),
    })),
    announcement: getAnnouncement(),
    terms: getTerms(),
    links: getLinks(),
  };
}

export function seedContent() {
  try {
    if (!getKv('terms')) {
      setKv('terms', JSON.stringify({ version: DEFAULT_TERMS_VERSION, body: DEFAULT_TERMS_BODY }));
    }

    const count = db.prepare('SELECT COUNT(*) AS n FROM credits').get().n;

    if (count === 0) {
      db.prepare('INSERT INTO credits (username, role, description, position, created_at) VALUES (?, ?, ?, ?, ?)')
        .run('Fudgedy', 'Creator & Developer', 'Built and maintains The Schematic Index.', 0, Date.now());
    }
  } catch (e) {
    console.error('[content] seed failed', e);
  }
}

seedContent();
