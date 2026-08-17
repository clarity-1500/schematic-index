package com.fudgedy.schematicindex;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

// Schematics opened with the "Load" button are cached here instead of the user's
// schematics folder. When Litematica unloads a schematic, the mixin on removeSchematic
// calls onUnloaded, which deletes the cached file, so the cache self-clears.
public final class LoadedCache {
	private LoadedCache() {
	}

	public static Path directory() {
		return FabricLoader.getInstance().getGameDir().resolve(SchematicIndexMod.MOD_ID).resolve("loaded");
	}

	public static Path resolve(String fileName) {
		return directory().resolve(fileName);
	}

	public static void onUnloaded(LitematicaSchematic schematic) {
		try {
			Path file = schematic.getFile();

			if (file == null) {
				return;
			}

			Path normalized = file.toAbsolutePath().normalize();

			if (normalized.startsWith(directory().toAbsolutePath().normalize())) {
				Files.deleteIfExists(normalized);
			}
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("Could not clear loaded-cache file", e);
		}
	}
}
