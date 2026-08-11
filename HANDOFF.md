# The Schematic Index — handoff

Everything needed to pick this up on another machine, without the chat history.

## What this is

A Litematica addon for **Minecraft 1.21.11 (Fabric)** aimed at DonutSMP: an in-game catalogue of
community schematics with a thumbnail grid, categories, likes, and a 3D preview. Posting is invite
only — the owner issues access codes to trusted Discord-server owners.

Current state: **client-side prototype, no backend**. Everything renders from a mock catalogue and
local files.

## Toolchain

| Component | Version |
|---|---|
| Minecraft | 1.21.11 |
| Java | 21 (Gradle must launch with 21+; the machine's default was 17) |
| Loom | 1.16-SNAPSHOT, Gradle 9.6.1 |
| Litematica | 0.26.12 · MaLiLib 0.27.16 (from `https://masa.dy.fi/maven/sakura-ryoko`) |
| Mappings | Mojang official — matches Litematica, **not** Yarn |

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21" ./gradlew build
JAVA_HOME="/c/Program Files/Java/jdk-21" ./gradlew runClient
```

Then load a world, press **M** for Litematica's menu, click **The Schematic Index** (top-right).

## Code map

```
SchematicIndexMod        client entrypoint
catalogue/               SchematicEntry (mirrors the future index JSON), Category, MockCatalogue
gui/Theme                palette, rounded rects, heart, arrows, blueprint placeholder
gui/IndexScreen          top bar, icon rail, chips, card grid, upload page, detail modal
gui/ImageStore           gallery images: decode off-thread, upload 2/frame, release on close
gui/SchematicPreview     isometric voxel renderer for .litematic files
gui/UploaderAccess       BETA access-code stub — must be deleted when the backend lands
mixin/LitematicaMainMenuMixin   the single hook into Litematica
```

## Decisions already made

- **Name**: The Schematic Index. Wordmark is "The Schematic" in white + "Index" in accent.
- **Categories** (fixed, uploader picks one): Farms, Contraptions, Regears, Stashes, Gambling Bases,
  Hangout Bases, Mega Builds.
- **Design**: Pinterest's discovery system inverted to dark chrome. Accent `#2A7A5B` only for the
  primary action, active chip and focus. Radii 2/3/4 only. Minecraft's default font throughout —
  hierarchy from scale, colour and `§l`, never below scale 1.0.
- **Uploads happen on a website, not in the mod** (decided, not yet built). Keeps the shipped client
  read-only and trivially auditable, and gives uploaders a real form with image cropping.
- **Auth**: access codes map to a *profile*. Codes stored hashed server-side, checked server-side,
  rate limited, revocable, and revoking a code must cascade to its sessions. One code per person.
- **Security posture**: no secrets in the jar. The client can be fully open source because nothing it
  holds grants any authority — see the notes in `UploaderAccess`.
- **Backend shape**: static read path (index JSON + files on a CDN, Cloudflare R2 for free egress),
  small authenticated write path. Validate uploads: size caps, NBT parses, reject command blocks.
- **Thumbnails**: uploader-supplied 16:9 images, server re-encoded to 480×270 (card) and 1280×720
  (detail). **Serve JPEG or PNG only** — Minecraft decodes via stb, which cannot read WebP, so a CDN
  auto-converting to WebP would blank every card.

## Gotchas discovered the hard way

- **Litematica's public `LitematicaSchematic(Path, CompoundTag, FileType)` constructor is broken** —
  it calls `readFromNBT()` before assigning its `converter` field, so any schematic old enough to need
  conversion throws NPE. Use `LitematicaSchematic.createFromFile(dir, name, type)`. Worth reporting
  upstream.
- 1.21.11 changed GUI input to event records: `mouseClicked(MouseButtonEvent, boolean)`,
  `keyPressed(KeyEvent)`, and `pose()` returns a `Matrix3x2fStack`.
- Litematica mixins need `remap = false` and their own mixin config file.
- Vanilla's `hud/heart/container` is a solid near-black heart, not an outline; the grey interior is
  drawn manually using the `hud/heart/full` pixel mask.
- Vanilla's arrow sprites are mid-grey and blit tinting only darkens, so arrows are drawn as triangles.

## Temporary scaffolding to remove

- `MockCatalogue` → the parsed index JSON
- `ImageStore` reading the local screenshots folder → downloaded thumbnails
- `SchematicPreview` reading the local schematics folder → the downloaded `.litematic`
- `UploaderAccess` beta codes (they are in the jar in plain text)
- In-memory posting

## Next steps

1. Index JSON schema + API contract (pagination, filtering, change detection) — everything else hangs
   off it, and changing it later breaks released clients.
2. Backend: read path first, then the authenticated write path.
3. Wire Download to actually fetch into `.minecraft/schematics` and hand the path to
   `SchematicHolder.getInstance().getOrLoad(path)`.
4. Confirm with DonutSMP staff that Litematica is allowed, and keep the mod obviously non-cheaty:
   no printer, no automation, no server packets.
