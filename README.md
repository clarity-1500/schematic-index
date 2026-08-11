# Schematic Library

An in-game catalogue of community schematics for [Litematica](https://modrinth.com/mod/litematica) —
browse thumbnails in a scrollable grid, filter by category, and download straight into your schematics
folder without leaving Minecraft.

**Status: layout preview.** There is no backend yet. Entries come from a local mock catalogue and
thumbnails are deterministic placeholders, so the menu can be designed and reviewed before any server
exists. Nothing in this build makes a network request.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.11 (Fabric) |
| Java | 21 |
| Litematica | 0.26.12 |
| MaLiLib | 0.27.16 |

Litematica and MaLiLib come from `https://masa.dy.fi/maven/sakura-ryoko` — the maintained line for
1.21.11 is sakura-ryoko's, since masa's own MaLiLib stops at 1.21.1.

## Building

Gradle must be launched with **Java 21 or newer**:

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21" ./gradlew build      # jar in build/libs/
JAVA_HOME="/c/Program Files/Java/jdk-21" ./gradlew runClient   # dev client with Litematica loaded
```

## Seeing the menu

1. `./gradlew runClient`
2. Load any world (the Litematica menu needs a world open)
3. Press **M** for Litematica's main menu
4. Click **Schematic Library**, top-right

## How it hooks into Litematica

Litematica builds its main menu from a hardcoded enum, so there is no addon API. `LitematicaMainMenuMixin`
injects at the tail of `GuiMainMenu.initGui` with `remap = false` (Litematica's classes are not in the
Minecraft mapping set) and extends MaLiLib's `GuiBase` to reuse `addButton` — the same approach
[Syncmatica](https://github.com/sakura-ryoko/syncmatica) uses. That is the only mixin in the mod.

Everything after the button is a plain vanilla `Screen`. MaLiLib's list widgets are row-based and fight
a thumbnail grid, so only the entry button uses MaLiLib.

## Design

Dark adaptation of a Pinterest-style discovery layout: quiet neutral chrome, imagery carries the page,
one saturated accent (`#2A7A5B`) reserved for the primary action, the active chip and focus — never
decorative.

| Token | Value | Use |
|---|---|---|
| accent | `#2A7A5B` | Download button, active chip |
| accent-bright | `#3FA87F` | Accent as *text* (the raw accent is only 3.4:1 on dark) |
| backdrop / surface / card | `#0F1114` / `#171A1E` / `#1E2227` | Chrome |
| text / mute / ash | `#F2F4F5` / `#A8B0B6` / `#6E767C` | Type |

Text is Minecraft's default font throughout. Since it is a single-weight bitmap face, hierarchy comes
from scale, colour and `§l` — never from a font size below 1.0, which turns to mush.

Rounded corners are drawn by stepping each corner row in along a circle (`Theme.roundedRect`), since
Minecraft has no rounded-rect primitive. Three radii only: 2 (pills), 3 (cards), 4 (modal).

### Layout

- Content column capped at 720px and centred, so ultrawide monitors don't stretch cards
- Columns by width: <340 → 2, <500 → 3, <660 → 4, else 5; 6px gutters
- Card = full-bleed 16:9 image + 20px caption strip (title, author, block count)
- Category overlay pill bottom-left, "Saved" badge top-right
- Detail modal over a 60% scrim, with exactly one accent button

At a 150px card the image is 150×84, which at GUI scale 3 is 450×252 real pixels — the reason the
planned thumbnail asset is 480×270.

## Layout of the code

```
catalogue/   SchematicEntry, Category, MockCatalogue   <- swap MockCatalogue for the real index later
gui/Theme    palette + rounded rect + text helpers
gui/LibraryScreen   top bar, chip row, card grid, scroll, detail modal
mixin/       the one Litematica hook
```

`SchematicEntry` deliberately mirrors what the index JSON will carry, so wiring up a backend does not
change the GUI.

## Not built yet

- Any networking, thumbnails, or downloads
- Real category list (the current six are placeholders)
- Settings screen showing the catalogue URL
- Offline cache and "update available" states
