package com.fudgedy.schematicindex;

import fi.dy.masa.litematica.data.DataManager;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * User preferences, written next to the mod's other config.
 *
 * <p>By default the download folder is Litematica's own
 * {@link DataManager#getSchematicsBaseDirectory()}, which resolves to the running game directory -
 * for a Modrinth or Prism setup that is whichever profile actually launched, so downloads land in
 * the right profile automatically. A player who would rather download elsewhere can override it, and
 * that override is stored; clearing it falls back to the automatic folder.
 */
public final class Settings {
	private static final String FILE_NAME = SchematicIndexMod.MOD_ID + ".properties";

	private static final String KEY_SOUNDS = "sound_effects";
	private static final String KEY_AUTO_LOAD = "load_after_download";
	private static final String KEY_CONFIRM_OVERWRITE = "confirm_overwrite";
	private static final String KEY_DOWNLOAD_DIR = "download_directory";

	private static boolean sounds = true;
	private static boolean autoLoad = true;
	private static boolean confirmOverwrite = true;
	private static @Nullable Path customDownloadDirectory;
	private static boolean loaded;

	private Settings() {
	}

	public static boolean sounds() {
		return sounds;
	}

	public static boolean autoLoad() {
		return autoLoad;
	}

	public static boolean confirmOverwrite() {
		return confirmOverwrite;
	}

	public static void toggleSounds() {
		sounds = !sounds;
		save();
	}

	public static void toggleAutoLoad() {
		autoLoad = !autoLoad;
		save();
	}

	public static void toggleConfirmOverwrite() {
		confirmOverwrite = !confirmOverwrite;
		save();
	}

	/**
	 * Where downloads land: the player's override if they set one, otherwise the schematics folder of
	 * the session that is running right now.
	 */
	public static Path downloadDirectory() {
		return customDownloadDirectory != null ? customDownloadDirectory : defaultDownloadDirectory();
	}

	/** The automatic location: the running session's Litematica schematics folder. */
	public static Path defaultDownloadDirectory() {
		try {
			return DataManager.getSchematicsBaseDirectory();
		} catch (Throwable e) {
			return FabricLoader.getInstance().getGameDir().resolve("schematics");
		}
	}

	public static boolean hasCustomDownloadDirectory() {
		return customDownloadDirectory != null;
	}

	public static void setDownloadDirectory(Path directory) {
		customDownloadDirectory = directory;
		save();
	}

	/** Drop the override and go back to following the running session. */
	public static void clearDownloadDirectory() {
		customDownloadDirectory = null;
		save();
	}

	public static void load() {
		if (loaded) {
			return;
		}

		loaded = true;
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

		if (!Files.exists(path)) {
			save();
			return;
		}

		Properties properties = new Properties();

		try (Reader reader = Files.newBufferedReader(path)) {
			properties.load(reader);
		} catch (IOException e) {
			SchematicIndexMod.LOGGER.warn("Could not read {}", path, e);
			return;
		}

		sounds = parse(properties.getProperty(KEY_SOUNDS), true);
		autoLoad = parse(properties.getProperty(KEY_AUTO_LOAD), true);
		confirmOverwrite = parse(properties.getProperty(KEY_CONFIRM_OVERWRITE), true);

		String stored = properties.getProperty(KEY_DOWNLOAD_DIR);
		customDownloadDirectory = stored == null || stored.isBlank() ? null : Path.of(stored.trim());
	}

	private static void save() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		Properties properties = new Properties();
		properties.setProperty(KEY_SOUNDS, Boolean.toString(sounds));
		properties.setProperty(KEY_AUTO_LOAD, Boolean.toString(autoLoad));
		properties.setProperty(KEY_CONFIRM_OVERWRITE, Boolean.toString(confirmOverwrite));

		if (customDownloadDirectory != null) {
			properties.setProperty(KEY_DOWNLOAD_DIR, customDownloadDirectory.toString());
		}

		try {
			Files.createDirectories(path.getParent());

			try (Writer writer = Files.newBufferedWriter(path)) {
				properties.store(writer, "The Schematic Index");
			}
		} catch (IOException e) {
			SchematicIndexMod.LOGGER.warn("Could not write {}", path, e);
		}
	}

	private static boolean parse(String value, boolean fallback) {
		return value == null ? fallback : Boolean.parseBoolean(value.trim());
	}
}
