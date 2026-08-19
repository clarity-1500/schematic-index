# Store listing — Network & Privacy disclosure

Paste the section below into the Modrinth / CurseForge project description (and keep a copy in
the in-game Terms of Service). It exists to satisfy the platforms' rule that a mod which makes
network requests must disclose what it sends, where, and why.

> ⚠️ Do **not** submit the Modrinth project for review yet — this file is the copy you'll use
> when you're ready. It does not trigger anything on its own.

---

## Network & Privacy

**The Schematic Index is an online mod.** It's a community catalogue: schematics live on a server,
and the mod fetches them so you can browse, preview, and download them in game. Because of that, it
talks to the internet. Here's exactly what it does and doesn't do.

### Nothing happens until you accept the Terms

On first launch the mod shows a Terms of Service screen and **makes no network requests until you
accept it.** If you decline, the mod closes and stays fully offline — no catalogue, no version
check, no requests of any kind. Accepting is what turns networking on.

### What the mod sends

- **An anonymous device identifier** — a random ID generated on your machine the first time you
  accept the Terms. It is **not** your Minecraft account, your username, your IP-as-identity, or any
  personal information; it's a random UUID that lets the server attribute your own likes, saved
  posts, ratings, and uploads back to you across sessions. You can generate a fresh one at any time
  with **Settings → Reset identifier**.
- **Your actions in the catalogue** — when you like, save, rate, download, follow, or upload, that
  action is sent to the catalogue server so it can be recorded (e.g. a download increments that
  post's counter). Uploading additionally sends the schematic file and images you chose to upload.
- Nothing else. The mod does not read your other mods, your files, your chat, your account, or your
  clipboard except when *you* press paste into one of its own text fields.

### Where it connects

| Host | Why |
|------|-----|
| `schematic-index-production.up.railway.app` | The catalogue itself: browsing, images, previews, downloads, likes/saves/ratings, uploads, and the version check. |
| `api.modrinth.com` / Modrinth CDN | Resolving and downloading the latest published version during the update check. |
| `mc-heads.net` | Rendering the player-head avatar next to a creator's name (derived from the in-game name shown on a post). |

The mod never puts personal data in a URL, and it only contacts the hosts above.

### Version check

The mod checks the catalogue server for a minimum supported version. By default, if your copy is
older than the minimum **and** a newer build is actually live on Modrinth, the mod asks you to
update before using the online features (older clients can send malformed data to the shared
server). **You can turn this off** in **Settings → Require the latest version** — with it off, an
out-of-date copy shows a one-time "update available" notice instead of requiring the update. The
check fails open: if the server can't be reached or your version can't be determined, the mod does
not lock you out.

### AI assistance

Parts of this project were developed with AI assistance.
