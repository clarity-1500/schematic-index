package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.fudgedy.schematicindex.Settings;
import com.fudgedy.schematicindex.gui.SchematicPreview;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class Backend {
	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(6))
			.build();

	private Backend() {
	}

	public static boolean configured() {
		return Settings.hasApiBaseUrl();
	}

	private static String base() {
		String url = Settings.apiBaseUrl();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	public static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	public static @Nullable JsonObject getJson(String path) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
					.timeout(Duration.ofSeconds(12))
					.header("X-Device-Token", Settings.deviceToken())
					.GET()
					.build();
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 400) {
				return null;
			}

			return JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("GET {} failed", path, e);
			return null;
		}
	}

	public static int postJson(String path, String jsonBody) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
					.timeout(Duration.ofSeconds(12))
					.header("Content-Type", "application/json")
					.header("X-Device-Token", Settings.deviceToken())
					.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
					.build();
			return CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("POST {} failed", path, e);
			return -1;
		}
	}

	public static void likeAsync(String postId, boolean like) {
		fireAndForget(like ? "/like" : "/unlike", one("postId", postId));
	}

	public static void downloadAsync(String postId) {
		fireAndForget("/download", one("postId", postId));
	}

	public static void reportAsync(String postId, String reason, String note) {
		JsonObject body = new JsonObject();
		body.addProperty("postId", postId);
		body.addProperty("reason", reason);
		body.addProperty("note", note);
		fireAndForget("/report", body.toString());
	}

	private static void fireAndForget(String path, String jsonBody) {
		if (!configured()) {
			return;
		}

		Thread worker = new Thread(() -> postJson(path, jsonBody), "schematicindex-post");
		worker.setDaemon(true);
		worker.start();
	}

	private static String one(String key, String value) {
		JsonObject o = new JsonObject();
		o.addProperty(key, value);
		return o.toString();
	}

	public static byte @Nullable [] getBytes(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(30))
					.GET()
					.build();
			HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
			return response.statusCode() >= 400 ? null : response.body();
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("GET bytes {} failed", url, e);
			return null;
		}
	}

	public static @Nullable String checkCode(String code) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/uploader"))
					.timeout(Duration.ofSeconds(10))
					.header("X-Upload-Code", code)
					.GET()
					.build();
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 400) {
				return null;
			}

			JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
			return body.has("valid") && body.get("valid").getAsBoolean() ? str(body, "displayName") : null;
		} catch (Throwable e) {
			return null;
		}
	}

	public static int upload(String code, String metaJson, Path schematic, List<Path> images) {
		try {
			String boundary = "----schematicindex" + System.nanoTime();
			byte[] body = multipart(boundary, metaJson, schematic, images);
			HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/upload"))
					.timeout(Duration.ofMinutes(2))
					.header("X-Upload-Code", code)
					.header("X-Device-Token", Settings.deviceToken())
					.header("Content-Type", "multipart/form-data; boundary=" + boundary)
					.POST(HttpRequest.BodyPublishers.ofByteArray(body))
					.build();
			return CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.warn("Upload failed", e);
			return -1;
		}
	}

	private static byte[] multipart(String boundary, String metaJson, Path schematic, List<Path> images) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		String dash = "--";
		String crlf = "\r\n";

		out.write((dash + boundary + crlf).getBytes(StandardCharsets.UTF_8));
		out.write(("Content-Disposition: form-data; name=\"meta\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
		out.write(metaJson.getBytes(StandardCharsets.UTF_8));
		out.write(crlf.getBytes(StandardCharsets.UTF_8));

		writeFilePart(out, boundary, "schematic", schematic.getFileName().toString(),
				"application/octet-stream", Files.readAllBytes(schematic));

		for (Path image : images) {
			String name = image.getFileName().toString().toLowerCase();
			String type = name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg" : "image/png";
			writeFilePart(out, boundary, "images", image.getFileName().toString(), type, Files.readAllBytes(image));
		}

		out.write((dash + boundary + dash + crlf).getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	private static void writeFilePart(ByteArrayOutputStream out, String boundary, String field, String filename,
			String contentType, byte[] data) throws Exception {
		String header = "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
				+ "Content-Type: " + contentType + "\r\n\r\n";
		out.write(header.getBytes(StandardCharsets.UTF_8));
		out.write(data);
		out.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	public static SchematicEntry parsePost(JsonObject o) {
		JsonObject size = o.getAsJsonObject("size");
		List<String> imageUrls = new ArrayList<>();

		if (o.has("imageUrls") && o.get("imageUrls").isJsonArray()) {
			for (JsonElement element : o.getAsJsonArray("imageUrls")) {
				imageUrls.add(element.getAsString());
			}
		}

		String fileUrl = str(o, "fileUrl");
		int slot = fileUrl != null ? SchematicPreview.registerUrl(fileUrl) : -1;

		return new SchematicEntry(
				str(o, "id"), str(o, "title"), str(o, "thumbnailName"), str(o, "poster"), str(o, "designer"),
				Category.fromName(str(o, "category")),
				intOf(size, "x"), intOf(size, "y"), intOf(size, "z"),
				intOf(o, "blockCount"), intOf(o, "downloads"), intOf(o, "likes"), longOf(o, "postedAt"),
				str(o, "description"), imageUrls.size(), 0, slot, false,
				str(o, "thumbnailUrl"), imageUrls, fileUrl, str(o, "fileHash"), longOf(o, "fileSize"),
				boolOf(o, "liked"));
	}

	public static NewsFeed.Entry parseNews(JsonObject o) {
		List<String> lines = new ArrayList<>();

		if (o.has("lines") && o.get("lines").isJsonArray()) {
			for (JsonElement element : o.getAsJsonArray("lines")) {
				lines.add(element.getAsString());
			}
		}

		return new NewsFeed.Entry(str(o, "badge"), str(o, "title"), str(o, "when"), lines, boolOf(o, "highlight"));
	}

	private static @Nullable String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static int intOf(@Nullable JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
	}

	private static long longOf(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0L;
	}

	private static boolean boolOf(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
	}
}
