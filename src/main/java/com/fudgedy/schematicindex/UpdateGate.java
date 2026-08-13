package com.fudgedy.schematicindex;

import com.fudgedy.schematicindex.catalogue.Backend;
import com.fudgedy.schematicindex.update.ModUpdater;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import org.jetbrains.annotations.Nullable;

public final class UpdateGate {
	private static volatile boolean checked;
	private static volatile boolean required;
	private static volatile boolean dismissed;
	private static volatile String minVersion = "";
	private static volatile String latestVersion = "";
	private static volatile String message = "";
	private static volatile String modrinthProject = "";
	private static volatile ModUpdater.Release release;

	private UpdateGate() {
	}

	public static String currentVersion() {
		return FabricLoader.getInstance().getModContainer(SchematicIndexMod.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("0.0.0");
	}

	public static boolean required() {
		return required;
	}

	public static boolean locked() {
		return required;
	}

	public static boolean checked() {
		return checked;
	}

	public static boolean shouldPrompt() {
		return required && !dismissed;
	}

	public static void dismiss() {
		dismissed = true;
	}

	public static String message() {
		return message;
	}

	public static String latestVersion() {
		return latestVersion;
	}

	public static String requiredVersion() {
		if (release != null) {
			return release.version();
		}

		if (!latestVersion.isBlank()) {
			return latestVersion;
		}

		return minVersion.isBlank() ? "the latest version" : minVersion;
	}

	public static @Nullable ModUpdater.Release release() {
		return release;
	}

	public static void checkAsync() {
		Thread worker = new Thread(UpdateGate::check, "schematicindex-version");
		worker.setDaemon(true);
		worker.start();
	}

	private static void check() {
		try {
			if (Boolean.getBoolean("schematicindex.forceLock")) {
				minVersion = System.getProperty("schematicindex.minVersion", "999.0.0");
				required = true;
				return;
			}

			String override = System.getProperty("schematicindex.minVersion");
			String min;
			String project;

			if (override != null) {
				min = override;
				project = System.getProperty("schematicindex.modrinth", "");
			} else {
				JsonObject body = Backend.getJson("/version");

				if (body == null) {
					return;
				}

				min = orEmpty(str(body, "minVersion"));
				latestVersion = orEmpty(str(body, "latestVersion"));
				message = orEmpty(str(body, "message"));
				project = orEmpty(str(body, "modrinth"));
			}

			minVersion = orEmpty(min);
			modrinthProject = project;

			if (min == null || min.isBlank() || !isBelow(currentVersion(), min)) {
				return;
			}

			ModUpdater.Release resolved = ModUpdater.resolveLatest(project);

			if (resolved != null && !isBelow(resolved.version(), min)) {
				release = resolved;
				required = true;
			} else {
				SchematicIndexMod.LOGGER.info(
						"Update required ({} below {}) but no installable Modrinth version is live yet",
						currentVersion(), min);
			}
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("Version check failed", e);
		} finally {
			checked = true;
		}
	}

	private static boolean isBelow(String current, String minimum) {
		try {
			return Version.parse(current).compareTo(Version.parse(minimum)) < 0;
		} catch (Throwable e) {
			return false;
		}
	}

	private static @Nullable String str(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
	}

	private static String orEmpty(@Nullable String value) {
		return value == null ? "" : value;
	}
}
