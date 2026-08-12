# The Schematic Index — Server

Backend API + admin site for The Schematic Index mod. Node + Express + SQLite.

## Run locally

```bash
cd server
cp .env.example .env      # then edit .env and set OWNER_KEY
npm install
npm start                 # http://localhost:8080
```

Check it's up:

```bash
curl http://localhost:8080/health
# {"ok":true,"service":"schematic-index","version":"0.1.0","posts":0}
```

## Layout

```
src/config.js   env config + file-URL helper
src/db.js       SQLite connection + schema (the whole data model)
src/app.js      Express app (endpoints added section by section)
src/index.js    entry point
data/           created at runtime: index.db + files/ (git-ignored)
```

## Data & files

- **SQLite** database at `DATA_DIR/index.db`. On a PaaS, set `DATA_DIR` to a persistent volume.
- **Uploaded files** on disk under `DATA_DIR/files/` (`img/` and `sch/`), served at `/files/...`.
- Nothing here is committed — `data/` and `.env` are git-ignored.

## Roadmap

- [x] Section 1 — skeleton + data model + `/health`
- [x] Section 2 — read API (`/index`, `/post/:id`, `/news`) + file serving
- [x] Section 3 — interactions (`/like`, `/unlike`, `/download`, `/report`) + rate limiting
- [x] Section 4 — upload API + code validation
- [x] Section 5 — admin API + owner auth
- [x] Section 6 — admin website (`/admin`)
- [x] Section 7 — reports → Discord
- [x] Section 8 — deployment config (Railway) — see DEPLOY.md
- [x] Section 9 — mod wired to the server (Backend/Catalogue/NewsFeed/uploads/interactions)
- [ ] Section 10 — deploy + launch (post-deploy: Railway + Modrinth)
