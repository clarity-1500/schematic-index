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

public final class Settings {
	private static final String FILE_NAME = SchematicIndexMod.MOD_ID + ".properties";

	private static final String KEY_SOUNDS = "sound_effects";
	private static final String KEY_UI_VOLUME = "ui_volume";
	private static final String KEY_CONFIRM_OVERWRITE = "confirm_overwrite";
	private static final String KEY_DOWNLOAD_DIR = "download_directory";
	private static final String KEY_GRID_DENSITY = "grid_density";
	private static final String KEY_TUTORIAL_SEEN = "tutorial_seen";
	private static final String KEY_TOASTS = "toasts";
	private static final String KEY_NOTIFICATIONS = "notifications";
	private static final String KEY_CREATOR_ALERTS = "creator_alerts";
	private static final String KEY_NOTIFICATIONS_SEEN = "notifications_seen_at";
	private static final String KEY_LAST_VISIT = "last_visit_at";
	private static final String KEY_UPDATE_LOCK = "update_lock";
	private static final String KEY_DEVICE_TOKEN = "device_token";
	private static final String KEY_TERMS = "terms_accepted";
	private static final String KEY_SKIP_DESIGNER_WARNING = "skip_designer_warning";
	private static final String KEY_TERMS_BODY = "terms_body";
	private static final String KEY_TERMS_VERSION = "terms_version";
	private static final String KEY_DISMISSED_ANNOUNCEMENT = "dismissed_announcement";

	private static final String OFFICIAL_API = "https://schematic-index-production.up.railway.app";

	private static boolean sounds = true;
	private static int uiVolume = 100;
	private static boolean confirmOverwrite = true;

	private static @Nullable String deviceToken;

	private static boolean termsAccepted;
	private static boolean skipDesignerWarning;

	private static @Nullable String cachedTermsBody;
	private static int cachedTermsVersion;
	private static @Nullable String dismissedAnnouncement;

	private static boolean toasts = true;

	private static boolean notifications = true;
	private static boolean creatorAlerts = true;
	private static long notificationsSeenAt;
	private static long lastVisitAt;
	private static boolean updateLock = true;
	private static @Nullable Path customDownloadDirectory;

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

	// Master UI sound volume as a percentage (0-100).
	public static int uiVolume() {
		return uiVolume;
	}

	public static float uiVolumeFraction() {
		return uiVolume / 100.0F;
	}

	public static void setUiVolume(int percent) {
		int clamped = Math.max(0, Math.min(100, percent));

		if (clamped != uiVolume) {
			uiVolume = clamped;
			save();
		}
	}

	public static void toggleConfirmOverwrite() {
		confirmOverwrite = !confirmOverwrite;
		save();
	}

	public static int gridDensity() {
		return gridDensity;
	}

	public static String gridDensityLabel() {
		return gridDensity < 0 ? "Large" : gridDensity > 0 ? "Compact" : "Comfortable";
	}

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

	// Follow & like alerts for signed-in uploaders (their own posts).
	public static boolean creatorAlerts() {
		return creatorAlerts;
	}

	public static void toggleCreatorAlerts() {
		creatorAlerts = !creatorAlerts;
		save();
	}

	public static long lastVisitAt() {
		return lastVisitAt;
	}

	public static void setLastVisitAt(long at) {
		lastVisitAt = at;
		save();
	}

	public static long notificationsSeenAt() {
		return notificationsSeenAt;
	}

	public static void setNotificationsSeenAt(long at) {
		if (at > notificationsSeenAt) {
			notificationsSeenAt = at;
			save();
		}
	}

	public static String deviceToken() {
		if (deviceToken == null || deviceToken.isBlank()) {
			deviceToken = java.util.UUID.randomUUID().toString();
			save();
		}

		return deviceToken;
	}

	// Replaces the anonymous per-install identifier with a fresh random one.
	public static void resetDeviceToken() {
		deviceToken = java.util.UUID.randomUUID().toString();
		save();
	}

	// When off, the remote version check only shows an update notice instead of locking the mod.
	public static boolean updateLockEnabled() {
		return updateLock;
	}

	public static void toggleUpdateLock() {
		updateLock = !updateLock;
		save();
	}

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

	public static boolean skipDesignerWarning() {
		return skipDesignerWarning;
	}

	public static void setSkipDesignerWarning(boolean value) {
		skipDesignerWarning = value;
		save();
	}

	public static @Nullable String cachedTermsBody() {
		return cachedTermsBody != null && !cachedTermsBody.isBlank() ? cachedTermsBody : null;
	}

	public static int cachedTermsVersion() {
		return cachedTermsVersion;
	}

	public static void cacheTerms(int version, String body) {
		if (body == null || body.isBlank()) {
			return;
		}

		if (version == cachedTermsVersion && body.equals(cachedTermsBody)) {
			return;
		}

		cachedTermsVersion = version;
		cachedTermsBody = body;
		save();
	}

	public static @Nullable String dismissedAnnouncement() {
		return dismissedAnnouncement;
	}

	public static void dismissAnnouncement(String id) {
		if (id == null || id.equals(dismissedAnnouncement)) {
			return;
		}

		dismissedAnnouncement = id;
		save();
	}

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

	public static Path downloadDirectory() {
		return customDownloadDirectory != null ? customDownloadDirectory : defaultDownloadDirectory();
	}

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
		uiVolume = Math.max(0, Math.min(100, parseInt(properties.getProperty(KEY_UI_VOLUME), 100)));
		confirmOverwrite = parse(properties.getProperty(KEY_CONFIRM_OVERWRITE), true);
		gridDensity = Math.max(-1, Math.min(1, parseInt(properties.getProperty(KEY_GRID_DENSITY), 0)));
		tutorialSeen = parse(properties.getProperty(KEY_TUTORIAL_SEEN), false);
		toasts = parse(properties.getProperty(KEY_TOASTS), true);
		notifications = parse(properties.getProperty(KEY_NOTIFICATIONS), true);
		creatorAlerts = parse(properties.getProperty(KEY_CREATOR_ALERTS), true);
		notificationsSeenAt = parseLong(properties.getProperty(KEY_NOTIFICATIONS_SEEN), 0L);
		lastVisitAt = parseLong(properties.getProperty(KEY_LAST_VISIT), 0L);
		updateLock = parse(properties.getProperty(KEY_UPDATE_LOCK), true);
		deviceToken = properties.getProperty(KEY_DEVICE_TOKEN, "");
		termsAccepted = parse(properties.getProperty(KEY_TERMS), false);
		skipDesignerWarning = parse(properties.getProperty(KEY_SKIP_DESIGNER_WARNING), false);
		cachedTermsBody = properties.getProperty(KEY_TERMS_BODY, "");
		cachedTermsVersion = parseInt(properties.getProperty(KEY_TERMS_VERSION), 0);
		dismissedAnnouncement = properties.getProperty(KEY_DISMISSED_ANNOUNCEMENT, "");

		String stored = properties.getProperty(KEY_DOWNLOAD_DIR);
		customDownloadDirectory = stored == null || stored.isBlank() ? null : Path.of(stored.trim());
	}

	private static void save() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		Properties properties = new Properties();
		properties.setProperty(KEY_SOUNDS, Boolean.toString(sounds));
		properties.setProperty(KEY_UI_VOLUME, Integer.toString(uiVolume));
		properties.setProperty(KEY_CONFIRM_OVERWRITE, Boolean.toString(confirmOverwrite));
		properties.setProperty(KEY_GRID_DENSITY, Integer.toString(gridDensity));
		properties.setProperty(KEY_TUTORIAL_SEEN, Boolean.toString(tutorialSeen));
		properties.setProperty(KEY_TOASTS, Boolean.toString(toasts));
		properties.setProperty(KEY_NOTIFICATIONS, Boolean.toString(notifications));
		properties.setProperty(KEY_CREATOR_ALERTS, Boolean.toString(creatorAlerts));
		properties.setProperty(KEY_NOTIFICATIONS_SEEN, Long.toString(notificationsSeenAt));
		properties.setProperty(KEY_LAST_VISIT, Long.toString(lastVisitAt));
		properties.setProperty(KEY_UPDATE_LOCK, Boolean.toString(updateLock));
		properties.setProperty(KEY_TERMS, Boolean.toString(termsAccepted));
		properties.setProperty(KEY_SKIP_DESIGNER_WARNING, Boolean.toString(skipDesignerWarning));
		properties.setProperty(KEY_TERMS_VERSION, Integer.toString(cachedTermsVersion));

		if (cachedTermsBody != null && !cachedTermsBody.isBlank()) {
			properties.setProperty(KEY_TERMS_BODY, cachedTermsBody);
		}

		if (dismissedAnnouncement != null && !dismissedAnnouncement.isBlank()) {
			properties.setProperty(KEY_DISMISSED_ANNOUNCEMENT, dismissedAnnouncement);
		}

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

	private static long parseLong(String value, long fallback) {
		if (value == null) {
			return fallback;
		}

		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
