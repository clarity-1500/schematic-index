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
	private static final String KEY_CONFIRM_OVERWRITE = "confirm_overwrite";
	private static final String KEY_DOWNLOAD_DIR = "download_directory";
	private static final String KEY_GRID_DENSITY = "grid_density";
	private static final String KEY_TUTORIAL_SEEN = "tutorial_seen";
	private static final String KEY_TOASTS = "toasts";
	private static final String KEY_NOTIFICATIONS = "notifications";
	private static final String KEY_DEVICE_TOKEN = "device_token";
	private static final String KEY_TERMS = "terms_accepted";

	/**
	 * The one official catalogue address, baked into the mod. It is deliberately not user-editable -
	 * there is a single service, and letting players repoint it only invites pointing at the wrong or a
	 * malicious server. A dev system property overrides it so testers can run against a local backend.
	 */
	private static final String OFFICIAL_API = "";

	private static boolean sounds = true;
	private static boolean confirmOverwrite = true;
	/** A random per-install id sent with likes and reports - not tied to any account. */
	private static @Nullable String deviceToken;
	/** Whether the player has agreed to the terms; gates the online features. */
	private static boolean termsAccepted;
	/** Whether toast cards are shown at all. */
	private static boolean toasts = true;
	/** Whether a followed creator's new post raises a notification. */
	private static boolean notifications = true;
	private static @Nullable Path customDownloadDirectory;
	/** Column offset from the width-based default: -1 = larger cards, +1 = more, smaller cards. */
	private static int gridDensity;
	private static boolean tutorialSeen;
	private static boolean loaded;

	private Settings() {
	}

	public static boolean sounds() {
		return sounds;
	}

	public static boolean confirmOverwrite() {
		return confirmOverwrite;
	}

	public static void toggleSounds() {
		sounds = !sounds;
		save();
	}

	public static void toggleConfirmOverwrite() {
		confirmOverwrite = !confirmOverwrite;
		save();
	}

	/** Column offset from the width-based default (-1 Large, 0 Comfortable, +1 Compact). */
	public static int gridDensity() {
		return gridDensity;
	}

	public static String gridDensityLabel() {
		return gridDensity < 0 ? "Large" : gridDensity > 0 ? "Compact" : "Comfortable";
	}

	/** Cycles Comfortable -> Compact -> Large -> Comfortable. */
	public static void cycleGridDensity() {
		gridDensity = gridDensity >= 1 ? -1 : gridDensity + 1;
		save();
	}

	public static boolean toasts() {
		return toasts;
	}

	public static void toggleToasts() {
		toasts = !toasts;
		save();
	}

	public static boolean notifications() {
		return notifications;
	}

	public static void toggleNotifications() {
		notifications = !notifications;
		save();
	}

	/** A stable random id for this install, created on first use and persisted. */
	public static String deviceToken() {
		if (deviceToken == null || deviceToken.isBlank()) {
			deviceToken = java.util.UUID.randomUUID().toString();
			save();
		}

		return deviceToken;
	}

	/** The catalogue base URL: the baked-in official address, or a dev system-property override. */
	public static String apiBaseUrl() {
		return System.getProperty("schematicindex.index", OFFICIAL_API);
	}

	public static boolean hasApiBaseUrl() {
		return apiBaseUrl() != null && !apiBaseUrl().isBlank();
	}

	public static boolean termsAccepted() {
		return termsAccepted;
	}

	public static void acceptTerms() {
		termsAccepted = true;
		save();
	}

	/** Withdraw agreement; the terms prompt shows again next time the Index opens. */
	public static void revokeTerms() {
		termsAccepted = false;
		save();
	}

	public static boolean tutorialSeen() {
		return tutorialSeen;
	}

	public static void markTutorialSeen() {
		if (!tutorialSeen) {
			tutorialSeen = true;
			save();
		}
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
		confirmOverwrite = parse(properties.getProperty(KEY_CONFIRM_OVERWRITE), true);
		gridDensity = Math.max(-1, Math.min(1, parseInt(properties.getProperty(KEY_GRID_DENSITY), 0)));
		tutorialSeen = parse(properties.getProperty(KEY_TUTORIAL_SEEN), false);
		toasts = parse(properties.getProperty(KEY_TOASTS), true);
		notifications = parse(properties.getProperty(KEY_NOTIFICATIONS), true);
		deviceToken = properties.getProperty(KEY_DEVICE_TOKEN, "");
		termsAccepted = parse(properties.getProperty(KEY_TERMS), false);

		String stored = properties.getProperty(KEY_DOWNLOAD_DIR);
		customDownloadDirectory = stored == null || stored.isBlank() ? null : Path.of(stored.trim());
	}

	private static void save() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		Properties properties = new Properties();
		properties.setProperty(KEY_SOUNDS, Boolean.toString(sounds));
		properties.setProperty(KEY_CONFIRM_OVERWRITE, Boolean.toString(confirmOverwrite));
		properties.setProperty(KEY_GRID_DENSITY, Integer.toString(gridDensity));
		properties.setProperty(KEY_TUTORIAL_SEEN, Boolean.toString(tutorialSeen));
		properties.setProperty(KEY_TOASTS, Boolean.toString(toasts));
		properties.setProperty(KEY_NOTIFICATIONS, Boolean.toString(notifications));
		properties.setProperty(KEY_TERMS, Boolean.toString(termsAccepted));

		if (deviceToken != null && !deviceToken.isBlank()) {
			properties.setProperty(KEY_DEVICE_TOKEN, deviceToken);
		}

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

	private static int parseInt(String value, int fallback) {
		if (value == null) {
			return fallback;
		}

		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
