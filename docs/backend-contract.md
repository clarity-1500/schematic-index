# The Schematic Index — Backend Contract

The single document the server is built against. The client (`SchematicEntry`, `Catalogue`,
`ImageStore`, `Download`, `MockCatalogue`, `UploaderAccess`) was deliberately shaped so that swapping
the mock source for these endpoints does not change the GUI. This spec pins the wire format so the
server and the client's networking layer can be built in parallel.

Status: **draft for build**. Two product choices are defaulted (see [Decisions](#decisions)); flip
them here first if you disagree, since they touch several endpoints.

---

## 1. Principles & Modrinth compliance

- **HTTPS only.** Every request and every asset URL is `https://`. HTTP is never used — it is the
  tamperable transport; HTTPS is encrypted and integrity-protected end to end.
- **Data only, never remote code.** The client downloads `.litematic` files and images and parses
  them locally. It must never download and execute code. This is the line that keeps the mod off
  Modrinth's malware list permanently.
- **Disclosed network use.** Modrinth's malware rule bans uploading data to a remote server *"without
  clear disclosure."* The project description must state that the mod connects to a catalogue server,
  downloads posts/images/schematics, and (for code holders) uploads them. A first-run notice in the
  mod restates this before any request is made.
- **Uploader-supplied rights.** The upload terms require the uploader to confirm they have the right
  to distribute the build (Modrinth's reupload rule flows down to them).

---

## 2. Identity & authentication

Three identities, no user accounts.

| Identity | Who | Where it lives | Sent as | Purpose |
|---|---|---|---|---|
| **Device token** | Every viewer | random UUID in the mod config, generated once | `X-Device-Token` | dedupe likes, attribute reports, rate-limit — **not** an account |
| **Upload code** | Trusted uploaders | entered in the mod, stored in config | `X-Upload-Code` | authorize uploads; revoking it is the real ban lever |
| **Owner key** | You only | your machine / server env — **never in the mod** | `X-Owner-Key` | delete posts, revoke codes |

Notes:
- The **device token is soft identity** by design. A user can wipe config and get a new one. That is
  acceptable because a viewer's worst case is a like/report they could redo anyway; rate limiting,
  not banning, is the real defence at this tier. Disclose it in config as "a random local ID used for
  likes and reports — not tied to your Minecraft or Modrinth account."
- The **upload code is the strong lever.** Content can only be created by a valid code, validated
  server-side on every upload. Banning an uploader = `POST /admin/revoke-code` + delete their posts.
- The **owner key never ships in the client.** Owner endpoints are called from a private admin tool
  or curl, authenticated by a secret only you hold.

---

## 3. Data model

### 3.1 Post (client-visible)

```json
{
  "id": "01J8Z0X4Q",
  "title": "Iron Farm — 300/h, no spawn-proofing",
  "thumbnailName": "Iron Farm 300/h",
  "poster": "Fudgedy",
  "designer": "SomeBuilder",
  "category": "FARMS",
  "size": { "x": 34, "y": 22, "z": 41 },
  "blockCount": 18452,
  "downloads": 1203,
  "likes": 210,
  "postedAt": 1734900000000,
  "description": "Overworld iron farm ...",
  "thumbnailUrl": "https://cdn.example/th/01J8Z0X4Q.png",
  "imageUrls": [
    "https://cdn.example/img/01J8Z0X4Q_0.png",
    "https://cdn.example/img/01J8Z0X4Q_1.png"
  ],
  "fileUrl": "https://cdn.example/sch/01J8Z0X4Q.litematic",
  "fileHash": "sha256:9f2b…",
  "fileSize": 48213,
  "liked": false
}
```

- `size.x/y/z` map to `SchematicEntry.sizeX/Y/Z`. `blockCount` is the real (non-air) block count;
  **Volume** is derived client-side as `x*y*z` (already implemented). `postedAt` is epoch millis UTC.
- `imageUrls` replaces the beta's local `imageStart`/`imageCount` indices — length is the gallery
  size. `thumbnailUrl` is the card image (may equal `imageUrls[0]`).
- `fileHash` (sha256) is the download integrity + cache key; the client can skip re-downloading a file
  whose hash it already has.
- `liked` is **per-device**: computed by the server from `X-Device-Token`. Absent/`false` when no
  token is sent.
- `category` is one of the fixed `Category` enum values (see 3.2). Never `ALL` — that is a client-side
  filter, not a stored value.

### 3.2 Category enum

`FARMS`, `CONTRAPTIONS`, `REGEARS`, `STASHES`, `GAMBLING_BASES`, `HANGOUT_BASES`, `MEGA_BUILDS`.
Must stay in lockstep with the client's `Category` enum. `ALL` is client-only.

### 3.3 Internal fields (never sent to clients)

`uploaderCodeId` (which code created the post — powers "revoke code → find their posts"),
`visibility` (`visible` | `hidden` | `deleted`), `reportCount`, `createdIp`/`createdToken` for abuse
triage. Clients only ever receive `visible` posts.

---

## 4. Endpoints

Base URL is configurable in the mod's Settings (promotes the current `-Dschematicindex.index` probe
to a real setting). All list/detail reads are unauthenticated except that sending `X-Device-Token`
enriches `liked`.

### 4.1 `GET /index` — paginated catalogue (infinite scroll)

Query: `category` (optional enum), `sort` (`newest`|`downloads`|`likes`, default `newest`),
`search` (optional string), `cursor` (opaque, from previous page), `limit` (default 24, max 60).

```json
{
  "posts": [ /* Post objects */ ],
  "nextCursor": "eyJvZmZzZXQiOjI0fQ==",
  "total": 512
}
```

- `nextCursor` is `null` on the last page. The client fetches page 1 on open and requests the next
  page as the scroll nears the bottom, appending results (the grid already virtualizes rows).
- Cursor pagination (not offset) so new uploads don't shift/duplicate rows mid-scroll.
- Server-side `search` and `sort` — the beta filters client-side; that moves to the server.

### 4.2 `GET /post/{id}` — single post

Returns one Post (with `liked` if a token is sent), `404` if not `visible`.

### 4.3 `POST /like` and `POST /unlike`

Auth: `X-Device-Token` (required). Body: `{ "postId": "…" }`. Idempotent — one like per token per
post. Response: `{ "likes": 211, "liked": true }`. Rate-limited (see §6).

### 4.4 `POST /download` — count a download

Auth: `X-Device-Token`. Body: `{ "postId": "…" }`. Increments the server download counter (deduped
per token per post per day). Called by the client when a download actually starts. The file itself is
fetched from `fileUrl` directly; this endpoint only records the count. Response `{ "downloads": 1204 }`.

### 4.5 `POST /report`

Auth: `X-Device-Token`. Body:

```json
{ "postId": "…", "reason": "STOLEN", "note": "optional free text ≤ 500 chars" }
```

`reason` ∈ `STOLEN` | `NSFW` | `BROKEN` | `SPAM` | `OTHER`. Server stores it, increments the post's
`reportCount`, and forwards to Discord (§5.2). Rate-limited. Response `{ "ok": true }`. One report per
token per post (re-reporting is a no-op).

### 4.6 `POST /upload` — create a post (multipart)

Auth: `X-Upload-Code` (required; `403` if missing/revoked). `multipart/form-data`:

- `schematic` — one `.litematic` file (≤ configurable max, e.g. 20 MB).
- `images` — 1–5 image files (png/jpg, each ≤ e.g. 8 MB). Matches the client's 1–5 picker.
- `meta` — JSON part: `{ title, thumbnailName, designer, category, description }`.

Server validates the code, extension, sizes, and count; parses the schematic for `size`/`blockCount`
server-side (don't trust client-supplied geometry); stores files in object storage; returns the
created Post. `poster` is derived from the code's registered display name. Publishes immediately
(no review queue) — trust is front-loaded onto the code.

### 4.7 `DELETE /post/{id}` — owner delete

Auth: `X-Owner-Key`. Soft-sets `visibility = deleted`. Reversible server-side. `204`.

### 4.8 `POST /admin/revoke-code` — ban an uploader

Auth: `X-Owner-Key`. Body `{ "code": "…", "deletePosts": true }`. Marks the code revoked and
optionally hides all posts with that `uploaderCodeId`. `200` with a count of affected posts.

### 4.9 `GET /health`

Unauthenticated liveness check the client uses to show the `OFFLINE` state (replaces the current HTTP
HEAD probe in `Catalogue`).

---

## 5. Moderation & reports

### 5.1 Lifecycle

Upload → immediately `visible`. Owner `DELETE` → `hidden`/`deleted`. Reports accumulate `reportCount`
but **do not** auto-hide by default (see [Decisions](#decisions)); the owner acts manually.

### 5.2 Report delivery — Discord webhook, server-side only

**The Discord webhook URL lives on the server, never in the mod.** If it shipped in the client, anyone
could decompile the jar and spam it. Flow: client → `POST /report` → server → Discord.

Each report (or each post crossing an optional threshold) posts a Discord message containing: post
title + thumbnail, `reason`, cumulative `reportCount`, reporter device token (for pattern-spotting),
and action hints — the post id to `DELETE` and the `uploaderCodeId` to revoke. Discord is your mod
queue at launch; a web admin view can come later. (Consistent with the project's existing
Discord-webhook logging pattern — but the secret stays server-side.)

---

## 6. Counts, dedupe & rate limits

- **Server-authoritative counts.** `likes` and `downloads` are owned by the server; the client only
  displays them. `liked` is per-device.
- **Dedupe:** like = 1 per (token, post); download = 1 per (token, post) per day; report = 1 per
  (token, post).
- **Rate limits (starting points, tune later):** likes 60/min/token; reports 5/hour/token; downloads
  30/min/token; uploads 10/day/code. Exceeding returns `429` with `Retry-After`.
- **Validation:** reject oversize/wrong-type uploads, over-long strings, unknown categories, malformed
  JSON. Never trust client-supplied `size`/`blockCount`/`poster` — derive them server-side.

---

## 7. Errors

JSON error envelope: `{ "error": "code", "message": "human text" }`. Status codes: `400` validation,
`403` bad/missing/revoked auth, `404` not found or not visible, `409` duplicate (e.g. re-like),
`413` payload too large, `429` rate limited, `5xx` server. The client maps `5xx`/timeouts to the
`Catalogue` `OFFLINE` state.

---

## 8. Client changes this contract implies

Mapped to existing classes, ordered by whether they gate server work.

**Do before/with the server (define the contract in code):**
- `SchematicEntry`: add `thumbnailUrl`, `imageUrls[]`, `fileUrl`, `fileHash`, `fileSize`, `liked`;
  drop the local `imageStart`/`imageCount` indexing in favour of `imageUrls`.
- `ImageStore`: add an HTTPS fetch path (download → the existing async decode/upload pipeline →
  cache), keyed by URL, alongside today's local-file path. Add a disk cache so re-launches are cheap.
- `Settings`: add the configurable **API base URL** (replaces `-Dschematicindex.index`).
- **Device token**: generate a UUID on first run, persist in `Settings`, send as `X-Device-Token`.

**Do when the server exists (consume the contract):**
- `Catalogue`: fetch + parse paginated `GET /index`; wire real `LOADING`/`READY`/`OFFLINE`.
- Infinite scroll: request `nextCursor` page as the grid nears the bottom and append.
- `Download`: point at `fileUrl` (the `url` param already exists) and verify `fileHash`.
- Upload form: `POST /upload` multipart with `X-Upload-Code`.
- Likes/downloads/reports: wire the `POST` endpoints; add a **Report** button + reason picker to the
  detail modal.
- `UploaderAccess`: validate the code against the server instead of the mock.

**Admin (separate from the mod):**
- A tiny owner tool (or curl snippets) for `DELETE /post/{id}` and `POST /admin/revoke-code` with the
  owner key.

---

## Decisions

Defaulted here; change in this section first if you disagree.

1. **Viewer identity = disclosed device token + rate limiting.** Not a profile. Needed so likes dedupe
   and reports attribute. To drop it entirely at launch: remove `X-Device-Token`, make likes anonymous
   (accept inflation), and attribute reports by IP only.
2. **Reports are manual-only by default.** `reportCount` accrues and pings Discord, but nothing
   auto-hides. An optional server config `autoHideThreshold` (e.g. 5) can enable auto-hide-pending-
   review later. Chosen so bad-faith reporters can't hide a trusted uploader's post.
