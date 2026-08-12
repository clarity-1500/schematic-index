# The Schematic Index — Handoff

## What this is

**The Schematic Index** is a client-side **Litematica addon for Minecraft 1.21.11 (Fabric)**, aimed at
**DonutSMP**. It adds an in-game, YouTube-style **catalogue of community schematics**: a thumbnail
grid with categories, likes, saves, a textured **3D preview**, invite-only uploads, a News/Changelog
tab, creator following, and Essential-style notification toasts.

It opens from **Litematica's own main menu** (a "The Schematic Index" button injected via mixin) into
its own full-screen UI. Everything runs **offline today** — posts come from a mock catalogue and
images/schematics from the local game folder — so the whole UI works with **no backend**. The design
goal is clean, non-suspicious, open-sourceable code (planned for Modrinth).

- Author handle: **Fudgedy** (packages are `com.fudgedy.schematicindex`). Do not use any real name.
- Brand colour: **#2A7A5B**. Font: Minecraft's default only.

## Build & run

- Fabric Loom `1.16-SNAPSHOT`, Gradle 9.6.1, **Java 21**, **Mojang official mappings**
  (`loom.officialMojangMappings()` — layered mappings, NOT Yarn).
- Deps: `fabric-loader`, MaLiLib `0.27.16`, Litematica `0.26.12` (masa maven), conditional-mixin
  (fallenbreath maven). **No fabric-api dependency.**
- JDK path used in this env: `/c/Program Files/Microsoft/jdk-21.0.10.7-hotspot`.
- Build: `JAVA_HOME=<jdk21> ./gradlew build --no-daemon`
- Run the dev client: `JAVA_HOME=<jdk21> ./gradlew runClient --no-daemon`
  - Test screenshots come from `%APPDATA%/ModrinthApp/profiles/Fabulously Optimized/screenshots`
    and test schematics from that profile's `schematics` folder (override with
    `-Dschematicindex.testschematics=<path>`).
  - Dev noise you can ignore: "Could not authorize you against Realms" and a locked `latest.log`.

## Architecture / file map (`src/main/java/com/fudgedy/schematicindex/`)

- `SchematicIndexMod` — `ClientModInitializer` entrypoint (just logs today).
- `Settings` — persisted prefs (`config/schematicindex.properties`): **Sound effects**,
  **Confirm before overwriting**, and an optional **custom download directory**. Default download
  dir = `DataManager.getSchematicsBaseDirectory()` (follows the running session → correct profile).
- **catalogue/**
  - `SchematicEntry` — one post (record). Has `title` (full name, detail panel) **and**
    `thumbnailName` (short, card) + `cardName()`, dimensions/blocks/downloads/likes, `postedAt`,
    `agoLabel()`, `imageCount/imageStart`, `schematicSlot`.
  - `MockCatalogue` — deterministic mock posts; `post()` inserts at front **and** calls
    `Follows.notifyForPost()`. Swap `entries()` for real index JSON later.
  - `Category` — fixed tags (Farms, Contraptions, Regears, Stashes, Gambling Bases, Hangout Bases,
    Mega Builds) + ALL.
  - `Catalogue` — LOADING/READY/OFFLINE state; probes `-Dschematicindex.index` via HTTP HEAD.
  - `Download` — streams a file to the download dir with **real byte progress**
    (`Progress{state,fraction,message}`), `.part` temp then move. Beta copies the local file.
  - `Follows` — followed creators (`config/schematicindex-follows.properties`); `notifyForPost()`
    raises a toast when a followed creator posts.
  - `NewsFeed` — mock News/Changelog entries (badge/title/date/body/highlight).
- **gui/**
  - `IndexScreen` — the whole UI (~1700 lines). Pages: **BROWSE, SAVED, NEWS, UPLOAD, SETTINGS**
    (icon rail, evenly spaced). Grid w/ virtualised scroll + skeleton loading, chip row, detail
    modal (picture carousel + 3D preview + options), upload form, settings page, news page.
  - `Theme` — palette + drawing primitives (rounded rect/outline, text/textScaled, clip/**clipBold**,
    `image`, blueprint placeholder, arrow, download glyph, heart/`heartPopped`, sound `click`).
    Also the **button animation registry** (`buttonHover`/`buttonPress`/`buttonScale`/`pushScale`/`pop`,
    `lighten`) and `DOWNLOAD_FILL`.
  - `SchematicPreview` — raycast voxel renderer (Amanatides-Woo DDA + Möller-Trumbore). Orbit/zoom,
    zoom-through-walls cutaway, free-look, **layer slider** (`maxLayer`), model cache **capped at 5**,
    `pathFor()` exposes the local file for downloads. 480×270 texture.
  - `BlockShapes` — real block geometry (baked model quads), chest entity sheets via `CHEST_MAPPER`,
    outline boxes for signs/beds/banners, **waterlogged → blue "wet" wash**.
  - `BlockTextures` — resolves sprites → pixels; tint via `BlockColors`; water special-case.
  - `ShapeTracer` — triangle intersection for non-cube blocks.
  - `ImageStore` — loads screenshots as GPU textures for thumbnails.
  - `IndexIcon` — the 12px chiseled-bookshelf `IGuiIcon` for the Litematica menu button.
  - `Toasts` — Essential-style bottom-left toasts: slide in from left, hold ~5.2s, slide out,
    depleting timer bar, item icon, cap 4 visible. `push(title, message, icon)`.
  - `UploaderAccess` — invite-code gate for uploads (mock).
- **mixin/**
  - `LitematicaMainMenuMixin` — injects the "The Schematic Index" button into Litematica's menu
    (`remap = false`, extends MaLiLib `GuiBase`).
  - `GuiHudMixin` — tail inject on vanilla `Gui.render(GuiGraphics, DeltaTracker)` to draw toasts
    over the in-game HUD (screens draw toasts themselves, so no double-draw).
  - Mixin config: `resources/schematicindex.litematica.mixins.json` (client list).

## Features implemented

- Litematica menu button → full custom screen; icon rail with 5 evenly-spaced tabs.
- Browse grid: categories chip row, sort (newest/downloads/likes), search, skeleton loading,
  offline state, virtualised scroll.
- Card: thumbnail image/blueprint placeholder, category tag, like heart (subtle pop), Saved badge,
  short thumbnail name (bold-clipped so it never breaks the border), poster + download count.
- Detail modal (+25% larger for pictures & 3D): picture carousel; **3D preview** with orbit, zoom,
  zoom-through-walls, free-look, **layer slider**; metadata (Dimensions/Blocks/Downloads/Likes);
  **Save for later**; **Follow** (compact, right of Posted by, with confirm-to-unfollow);
  **Download** button that doubles as a real progress bar; like heart.
- Upload form (invite-gated): Schematic name + Thumbnail name (both capped to fit), Designer,
  Description, Category, .litematic picker, 1–5 picture picker, roomy spacing.
- Settings tab: **Sound effects** toggle (gates all UI SFX), **Confirm before overwriting** toggle,
  **Download folder** (auto-assigned, with **Change** folder picker / **Open folder** / **Reset**).
- News/Changelog tab: scrollable news cards.
- Follow creators (persisted) + **Essential-style toasts** over HUD and screen; a followed creator's
  new post raises a toast.
- Button feel everywhere: hover scale 1.02× (eased ~200ms) + lighter fill; click dip to 0.98×.

## Known stubs / limitations (be honest with the user)

- **No backend.** Posts, likes, downloads, follows-notifications are local/in-memory or local files.
- **Confirm before overwriting** is a stored preference but **not enforced** — `Download` currently
  replaces same-name files silently. Needs a real prompt wired into the download flow.
- **Waterlogged blocks** render as a **blue wash on the block**, not real water — the raycaster has no
  translucency, so a real water volume would occlude the block as a solid blue cube. Real translucent
  water needs alpha compositing in `ShapeTracer`/`SchematicPreview` (bigger change).
- **Block-entity textures**: chests use their true entity sheet; signs/beds/banners fall back to
  shaped outline boxes skinned with the particle texture. Shulker boxes (high value for stashes) not
  yet mapped.
- "Followed creator posts" can only trigger in beta by posting in-game as a followed name (no live
  feed). The `Follows.notifyForPost` hook is the same one a real feed poll will call.

## Planned / next steps

- **Backend/index**: real catalogue JSON + upload flow; access-code issuance; likes/downloads server.
- **Sidebar tabs to consider** (discussed; strongest first): Downloads/My Library, Creators,
  Materials/Shopping-list, My Uploads/Profile, Verified/Staff picks, Requests/Wanted, Report/Mod
  queue, Random, News (done), Help/How-to. Recommended first four: Downloads, Creators, Materials,
  My Uploads.
- **Wire the overwrite prompt** (or drop the toggle).
- **Auto-load into Litematica after download** (previously removed — re-add only if wanted, wired).
- **Real translucent water** and **broader block-entity textures** (shulkers next).
- Possibly a "Following" feed tab and per-creator pages.

## Gotchas / history

- A PowerShell mishap once **emptied every Java file**; recovered from
  `build/devlibs/*-sources.jar` (the **unremapped**, Mojang-mapped copy — the `build/libs` sources
  jar is intermediary-remapped and won't compile). If it ever happens again, prefer `build/devlibs`.
- 1.21.11 API notes: GUI uses `MouseButtonEvent`/`KeyEvent`; `pose()` is `Matrix3x2fStack`
  (`pushMatrix`/`popMatrix`/`translate`/`scale`); `NativeImage.setPixel` is ARGB; MaLiLib icons blit
  at 1/256 so icon sheets must be 256×256 and the icon's reported width strides the state copies;
  open a folder via `net.minecraft.util.Util.getPlatform().openPath(Path)`; HUD render is
  `Gui.render(GuiGraphics, DeltaTracker)`.
- Litematica: use `LitematicaSchematic.createFromFile(dir, name, type)` (public ctor NPEs); parse on
  the render thread (MaLiLib message system bakes font glyphs).
