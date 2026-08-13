package com.fudgedy.schematicindex.update;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ModUpdater {
	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.build();

	private static final String GAME_VERSION = "1.21.11";
	private static final String LOADER = "fabric";
	private static final String USER_AGENT = "fudgedy/schematicindex (update-check)";

	public record Release(String version) {
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

			String version = str(best, "version_number");
			return version == null ? null : new Release(version);
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("Modrinth resolve failed", e);
			return null;
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
