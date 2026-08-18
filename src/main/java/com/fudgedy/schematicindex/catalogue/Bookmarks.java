package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class Bookmarks {
	private static final String FILE_NAME = SchematicIndexMod.MOD_ID + "-library.properties";
	private static final String KEY_LIKED = "liked";
	private static final String KEY_SAVED = "saved";

	private static final Set<String> LIKED = new LinkedHashSet<>();
	private static final Set<String> SAVED = new LinkedHashSet<>();
	private static boolean loaded;

	private Bookmarks() {
	}

	public static boolean isLiked(String id) {
		ensureLoaded();
		return LIKED.contains(id);
	}

	// Saved post ids in the order they were saved (oldest first).
	public static List<String> savedOrder() {
		ensureLoaded();
		return new ArrayList<>(SAVED);
	}

	public static boolean toggleLike(String id) {
		ensureLoaded();
		boolean nowLiked = !LIKED.remove(id);

		if (nowLiked) {
			LIKED.add(id);
		}

		save();
		return nowLiked;
	}

	public static boolean isSaved(String id) {
		ensureLoaded();
		return SAVED.contains(id);
	}

	public static boolean toggleSaved(String id) {
		ensureLoaded();
		boolean nowSaved = !SAVED.remove(id);

		if (nowSaved) {
			SAVED.add(id);
		}

		save();
		return nowSaved;
	}

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}

		loaded = true;
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

		if (!Files.exists(path)) {
			return;
		}

		Properties properties = new Properties();

		try (Reader reader = Files.newBufferedReader(path)) {
			properties.load(reader);
		} catch (IOException e) {
			SchematicIndexMod.LOGGER.warn("Could not read {}", path, e);
			return;
		}

		readInto(properties.getProperty(KEY_LIKED, ""), LIKED);
		readInto(properties.getProperty(KEY_SAVED, ""), SAVED);
	}

	private static void readInto(String stored, Set<String> into) {
		for (String id : stored.split(",")) {
			if (!id.isBlank()) {
				into.add(id.trim());
			}
		}
	}

	private static void save() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		Properties properties = new Properties();
		properties.setProperty(KEY_LIKED, String.join(",", LIKED));
		properties.setProperty(KEY_SAVED, String.join(",", SAVED));

		try {
			Files.createDirectories(path.getParent());

			try (Writer writer = Files.newBufferedWriter(path)) {
				properties.store(writer, "The Schematic Index - liked and saved posts");
			}
		} catch (IOException e) {
			SchematicIndexMod.LOGGER.warn("Could not write {}", path, e);
		}
	}
}
