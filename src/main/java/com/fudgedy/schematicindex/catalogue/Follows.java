package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.fudgedy.schematicindex.Settings;
import com.fudgedy.schematicindex.gui.Toasts;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class Follows {
	private static final String FILE_NAME = SchematicIndexMod.MOD_ID + "-follows.properties";
	private static final String KEY = "following";

	private static final Set<String> FOLLOWING = new LinkedHashSet<>();
	private static boolean loaded;

	private Follows() {
	}

	public static boolean isFollowing(String poster) {
		ensureLoaded();
		return FOLLOWING.contains(key(poster));
	}

	public static boolean toggle(String poster) {
		ensureLoaded();
		boolean nowFollowing;

		if (FOLLOWING.remove(key(poster))) {
			nowFollowing = false;
		} else {
			FOLLOWING.add(key(poster));
			nowFollowing = true;
		}

		save();
		return nowFollowing;
	}

	public static int count() {
		ensureLoaded();
		return FOLLOWING.size();
	}

	public static void notifyForPost(SchematicEntry entry) {
		if (!Settings.notifications() || !isFollowing(entry.poster())) {
			return;
		}

		Toasts.push(entry.poster() + " posted", entry.title(), new ItemStack(Items.WRITABLE_BOOK));
	}

	private static String key(String poster) {
		return poster == null ? "" : poster.trim().toLowerCase(Locale.ROOT);
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

		String stored = properties.getProperty(KEY, "");

		for (String name : stored.split(",")) {
			if (!name.isBlank()) {
				FOLLOWING.add(name.trim());
			}
		}
	}

	private static void save() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		Properties properties = new Properties();
		properties.setProperty(KEY, String.join(",", FOLLOWING));

		try {
			Files.createDirectories(path.getParent());

			try (Writer writer = Files.newBufferedWriter(path)) {
				properties.store(writer, "The Schematic Index - followed creators");
			}
		} catch (IOException e) {
			SchematicIndexMod.LOGGER.warn("Could not write {}", path, e);
		}
	}

	public static Set<String> all() {
		ensureLoaded();
		return new LinkedHashSet<>(FOLLOWING);
	}
}
