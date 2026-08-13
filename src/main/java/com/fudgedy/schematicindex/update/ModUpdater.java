package com.fudgedy.schematicindex.update;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Consumer;

public final class ModUpdater {
	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.build();

	private static final String GAME_VERSION = "1.21.11";
	private static final String LOADER = "fabric";
	private static final String USER_AGENT = "fudgedy/schematicindex (auto-update)";

	public record Release(String version, String fileUrl, String fileName) {
	}

	private ModUpdater() {
	}

	public static @Nullable Release resolveLatest(String project) {
		if (project == null || project.isBlank()) {
			return null;
		}

		try {
			String url = "https://api.modrinth.com/v2/project/" + project + "/version"
					+ "?loaders=" + enc("[\"" + LOADER + "\"]")
					+ "&game_versions=" + enc("[\"" + GAME_VERSION + "\"]");
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(12))
					.header("User-Agent", USER_AGENT)
					.GET()
					.build();
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 400) {
				return null;
			}

			JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
			JsonObject best = null;
			String bestDate = "";

			for (JsonElement element : versions) {
				JsonObject version = element.getAsJsonObject();
				String date = orEmpty(str(version, "date_published"));

				if (best == null || date.compareTo(bestDate) > 0) {
					best = version;
					bestDate = date;
				}
			}

			if (best == null) {
				return null;
			}

			JsonObject file = primaryFile(best);

			if (file == null) {
				return null;
			}

			String version = str(best, "version_number");
			String fileUrl = str(file, "url");
			String fileName = str(file, "filename");

			if (version == null || fileUrl == null || fileName == null) {
				return null;
			}

			return new Release(version, fileUrl, fileName);
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("Modrinth resolve failed", e);
			return null;
		}
	}

	private static @Nullable JsonObject primaryFile(JsonObject version) {
		if (!version.has("files") || !version.get("files").isJsonArray()) {
			return null;
		}

		JsonArray files = version.getAsJsonArray("files");
		JsonObject first = null;

		for (JsonElement element : files) {
			JsonObject file = element.getAsJsonObject();

			if (first == null) {
				first = file;
			}

			if (file.has("primary") && file.get("primary").getAsBoolean()) {
				return file;
			}
		}

		return first;
	}

	public static void install(Release release, Consumer<String> status) {
		try {
			Path currentJar = currentJar();

			if (currentJar == null) {
				status.accept("Auto-update is not available in this environment. Update manually from Modrinth.");
				return;
			}

			status.accept("Downloading " + release.fileName() + "...");
			Path staging = FabricLoader.getInstance().getGameDir()
					.resolve(SchematicIndexMod.MOD_ID).resolve("update");
			Files.createDirectories(staging);
			Path staged = staging.resolve(release.fileName());

			if (!download(release.fileUrl(), staged)) {
				status.accept("Download failed. Please try again.");
				return;
			}

			Path target = currentJar.getParent().resolve(release.fileName());
			status.accept("Preparing update...");

			if (!writeAndSpawnSwapper(currentJar, staged, target)) {
				status.accept("Downloaded to " + staged + ". Replace the mod jar and restart.");
				return;
			}

			status.accept("Update ready. Minecraft will now close to finish updating.");
			Thread closer = new Thread(() -> {
				try {
					Thread.sleep(1600L);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}

				Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
			}, "schematicindex-update-close");
			closer.setDaemon(true);
			closer.start();
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.warn("Update install failed", e);
			status.accept("Update failed: " + e.getClass().getSimpleName());
		}
	}

	private static @Nullable Path currentJar() {
		ModContainer container = FabricLoader.getInstance().getModContainer(SchematicIndexMod.MOD_ID).orElse(null);

		if (container == null) {
			return null;
		}

		for (Path path : container.getOrigin().getPaths()) {
			if (path != null && path.getFileName() != null
					&& path.getFileName().toString().endsWith(".jar") && Files.isRegularFile(path)) {
				return path;
			}
		}

		return null;
	}

	private static boolean download(String url, Path target) {
		try {
			Files.createDirectories(target.getParent());
			Path temporary = target.resolveSibling(target.getFileName() + ".part");
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofMinutes(3))
					.header("User-Agent", USER_AGENT)
					.GET()
					.build();
			HttpResponse<Path> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(temporary));

			if (response.statusCode() >= 400) {
				Files.deleteIfExists(temporary);
				return false;
			}

			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.warn("Update download failed", e);
			return false;
		}
	}

	private static boolean writeAndSpawnSwapper(Path current, Path staged, Path target) {
		try {
			boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
			Path dir = staged.getParent();

			if (windows) {
				Path script = dir.resolve("swap.bat");
				Files.writeString(script, "@echo off\r\n"
						+ ":wait\r\n"
						+ "timeout /t 1 /nobreak >nul\r\n"
						+ "del \"" + current + "\" >nul 2>&1\r\n"
						+ "if exist \"" + current + "\" goto wait\r\n"
						+ "move /y \"" + staged + "\" \"" + target + "\" >nul 2>&1\r\n"
						+ "del \"%~f0\"\r\n");
				new ProcessBuilder("cmd.exe", "/c", "start", "", "/min", "cmd.exe", "/c", script.toString()).start();
				return true;
			}

			Path script = dir.resolve("swap.sh");
			Files.writeString(script, "#!/bin/sh\n"
					+ "while ! rm \"" + current + "\" 2>/dev/null; do sleep 1; done\n"
					+ "mv \"" + staged + "\" \"" + target + "\"\n"
					+ "rm -- \"$0\"\n");
			new ProcessBuilder("sh", "-c", "nohup sh \"" + script + "\" >/dev/null 2>&1 &").start();
			return true;
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.warn("Could not spawn updater", e);
			return false;
		}
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static @Nullable String str(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
	}

	private static String orEmpty(@Nullable String value) {
		return value == null ? "" : value;
	}
}
