# The Schematic Index

An in-game catalogue of community schematics for [Litematica](https://modrinth.com/mod/litematica).
Browse posts in a scrollable youtube style grid, filter by categories and tags, preview a build in 3D within the mod, and download it straight
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

- A catalogue browser opened from Litematica's main menu: categories, tags, search bar, sorting filters, and the posts themselves. You can like posts, and even follow creators to get an in game notification whenever they post a new schematic. 
- A 3D preview of the build per post: orbit, zoom in and out or cut away layers.
- Downloads that get directed straight into your schematics folder for that client, ( changeable in settings ), 
- Uploading posts is currently beta and is only given to few trusted people, it eventually will be fully open for anyone to post their own schematics. a

## Build

```
JAVA_HOME=<jdk21> ./gradlew build
```

The jar lands in `build/libs/`. To run a dev client on your machine: `./gradlew runClient`.

By default, the mod connects to the official server to load the uploaded posts and data into your client: `Settings.OFFICIAL_API`. 
For local development for a server on your machine, launch with `-Dschematicindex.index=http://localhost:8080`.

## Server

The backend is a Node service (Express + SQLite) that exposes the catalogue API and an admin control site where you can manage posts, reports, and people who have access to upload ( for now ). See
[`server/README.md`](server/README.md) to run it and [`server/DEPLOY.md`](server/DEPLOY.md) to deploy.

## License

See [LICENSE](LICENSE).
