# The Schematic Index - Server

Backend API and admin panel for The Schematic Index mod. Node, Express, and SQLite (via the built-in
`node:sqlite`, so there are no native modules to compile).

## Run locally

```bash
cd server
cp .env.example .env      # then set OWNER_KEY
npm install
npm start                 # http://localhost:8080
```

Check it is up:

```bash
curl http://localhost:8080/health
```

Seed the news feed and a couple of sample posts for development:

```bash
npm run seed
```

## Layout

```
src/config.js        environment config and the file-URL helper
src/db.js            SQLite connection and schema
src/serialize.js     row -> API JSON
src/read.js          GET /index, /post/:id, /news, and /files static hosting
src/interactions.js  POST /like, /unlike, /download, /report
src/upload.js        POST /upload (code auth, .litematic parsing), GET /uploader
src/admin.js         owner-authenticated admin API and the /admin page
src/discord.js       forwards reports to a Discord webhook
src/ratelimit.js     per-token rate limiting
src/app.js           wires the routes together
src/index.js         entry point
public/admin.html    the admin single-page app
```

## Data and files

- SQLite database at `DATA_DIR/index.db`. On a host, point `DATA_DIR` at a persistent volume.
- Uploaded files under `DATA_DIR/files/` (`img/` and `sch/`), served at `/files/...`.
- `data/` and `.env` are git-ignored.

## Endpoints

Read: `GET /index` (paginated, with category/search/sort), `GET /post/:id`, `GET /news`,
`GET /files/...`, `GET /health`.

Interactions (require an `X-Device-Token` header): `POST /like`, `/unlike`, `/download`, `/report`.

Uploads (require an `X-Upload-Code` header): `POST /upload`, and `GET /uploader` to validate a code.

Admin (require an `X-Owner-Key` header): manage posts, news, reports, and upload codes under
`/admin/api/...`, with the panel served at `/admin`.

See [DEPLOY.md](DEPLOY.md) for hosting.
