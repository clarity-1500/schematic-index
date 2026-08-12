# The Schematic Index

An in-game catalogue of community schematics for [Litematica](https://modrinth.com/mod/litematica).
Browse posts in a scrollable grid, filter by category, preview a build in 3D, and download it straight
into your schematics folder without leaving Minecraft.

The repository holds two parts: the Fabric client mod (`src/`) and the backend that serves the
catalogue plus a small admin panel (`server/`).

## Requirements

| | |
|---|---|
| Minecraft | 1.21.11 (Fabric) |
| Java | 21 |
| Dependencies | Fabric Loader, MaLiLib 0.27.16, Litematica 0.26.12 |

## Features

- A catalogue browser opened from Litematica's main menu: category chips, search, sort, and infinite
  scroll over a virtualised grid.
- A textured 3D preview per post: orbit, zoom, cut-away, a layer slider, translucent water, and real
  block-entity textures.
- Downloads that stream into your schematics folder with real progress, likes and saved posts that
  persist, creator follows, in-app notifications, and a news tab.
- Invite-code uploads and a report flow, both backed by the server.

## Build

```
JAVA_HOME=<jdk21> ./gradlew build
```

The jar lands in `build/libs/`. To run a dev client: `./gradlew runClient`.

By default the mod connects to the catalogue address baked into `Settings.OFFICIAL_API`. For local
development against a server on your machine, launch with `-Dschematicindex.index=http://localhost:8080`.

## Server

The backend is a Node service (Express + SQLite) that exposes the catalogue API and an admin site. See
[`server/README.md`](server/README.md) to run it and [`server/DEPLOY.md`](server/DEPLOY.md) to deploy.

## License

See [LICENSE](LICENSE).
