# Deploying to Railway

The server is a plain Node app (Nixpacks auto-detects it) with two things it needs in production: a
**persistent volume** for the SQLite database + uploaded files, and a few **environment variables**.

## 1. Create the project

1. Push this repo to GitHub (the `server/` folder is what Railway builds).
2. In Railway: **New Project -> Deploy from GitHub repo**, pick the repo.
3. If the repo root isn't the server, set the service **Root Directory** to `server`.

Railway reads `railway.json` (start command `npm start`, health check `/health`) and `engines.node`
(`>=22`, which is what `node:sqlite` needs).

## 2. Add a persistent volume

SQLite and uploaded files must survive restarts.

1. Service -> **Variables/Settings -> Volumes -> New Volume**.
2. Mount path: **`/data`**.

## 3. Set environment variables

Service -> **Variables**:

| Variable | Value |
|---|---|
| `DATA_DIR` | `/data` |
| `OWNER_KEY` | a long random secret (see below) |
| `PUBLIC_BASE` | your Railway URL, e.g. `https://schematic-index-production.up.railway.app` |
| `DISCORD_WEBHOOK` | *(optional)* your private Discord webhook URL for reports |

`PORT` is provided by Railway automatically - don't set it.

Generate an owner key:

```bash
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
```

## 4. Deploy & verify

Railway builds and deploys on push. Then:

```bash
curl https://YOUR-RAILWAY-URL/health
# {"ok":true,"service":"schematic-index",...}
```

Open `https://YOUR-RAILWAY-URL/admin`, sign in with `OWNER_KEY`, and generate your first upload code.

## 5. Point the mod at it

Set `OFFICIAL_API` in the mod's `Settings.java` to your `PUBLIC_BASE` URL, rebuild, and the mod talks
to the live catalogue.

## Notes

- **Data is only in the volume.** Back it up periodically (SQLite is one file at `/data/index.db`;
  uploads are under `/data/files/`). Railway can snapshot volumes.
- No native modules - `node:sqlite`, `multer`, and `prismarine-nbt` are all pure JS, so builds are fast
  and never fail on a missing compiler.
