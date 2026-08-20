package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.fudgedy.schematicindex.Settings;
import com.fudgedy.schematicindex.gui.SchematicPreview;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public final class Backend {
	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(6))
			.build();

	private Backend() {
	}

	// Networking is gated on the user having accepted the terms - nothing contacts the server
	// until then, and revoking consent in Settings turns it back off.
	public static boolean configured() {
		return Settings.hasApiBaseUrl() && Settings.termsAccepted();
	}

	private static String base() {
		String url = Settings.apiBaseUrl();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	public static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	public static @Nullable JsonObject getJson(String path) {
		return get(path, true);
	}

	// A GET that carries no device token - used for the anonymous /version check.
	public static @Nullable JsonObject getJsonAnon(String path) {
		return get(path, false);
	}

	private static @Nullable JsonObject get(String path, boolean identified) {
		if (!configured()) {
			return null;
		}

		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
					.timeout(Duration.ofSeconds(12))
					.header("Accept-Encoding", "gzip");

			if (identified) {
				builder.header("X-Device-Token", Settings.deviceToken());
			}

			HttpRequest request = builder.GET().build();
			HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

			if (response.statusCode() >= 400) {
				return null;
			}

			byte[] body = response.body();
			String encoding = response.headers().firstValue("Content-Encoding").orElse("");

			// The server runs compression() but only when we ask; fall back to the raw bytes
			// if it decided to answer uncompressed anyway.
			if (encoding.toLowerCase().contains("gzip")) {
				try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
					body = gz.readAllBytes();
				}
			}

			String json = new String(body, StandardCharsets.UTF_8);
			return JsonParser.parseString(json).getAsJsonObject();
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("GET {} failed", path, e);
			return null;
		}
	}

	public static int postJson(String path, String jsonBody) {
		if (!configured()) {
			return -1;
		}

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

	// Anonymous presence beat: registers this install (counted in "total users") and refreshes
	// its server-side "online" timestamp. Carries only the anonymous device token and the mod
	// version - nothing identifying - and no-ops entirely until the terms are accepted. Runs
	// synchronously, so callers must invoke it off the main thread.
	public static void heartbeat(String version) {
		if (!configured()) {
			return;
		}

		int status = postJson("/presence", one("version", version == null ? "" : version));
		SchematicIndexMod.LOGGER.debug("Presence heartbeat -> {}", status);
	}

	public static void likeAsync(String postId, boolean like) {
		fireAndForget(like ? "/like" : "/unlike", one("postId", postId));
	}

	public static void downloadAsync(String postId) {
		fireAndForget("/download", one("postId", postId));
	}

	public static void viewAsync(String postId) {
		fireAndForget("/view", one("postId", postId));
	}

	public static void followAsync(String postId, String poster) {
		JsonObject body = new JsonObject();
		body.addProperty("postId", postId == null ? "" : postId);
		body.addProperty("poster", poster);
		fireAndForget("/follow", body.toString());
	}

	public static void unfollowAsync(String poster) {
		fireAndForget("/unfollow", one("poster", poster));
	}

	// Synchronous variants that return the HTTP status so the caller can revert the
	// optimistic UI and surface an error when the follow could not be saved (e.g. offline).
	public static int followStatus(String postId, String poster) {
		if (!configured()) {
			return 0;
		}

		JsonObject body = new JsonObject();
		body.addProperty("postId", postId == null ? "" : postId);
		body.addProperty("poster", poster);
		return postJson("/follow", body.toString());
	}

	public static int unfollowStatus(String poster) {
		if (!configured()) {
			return 0;
		}

		return postJson("/unfollow", one("poster", poster));
	}

	public static void reportAsync(String postId, String reason, String note) {
		JsonObject body = new JsonObject();
		body.addProperty("postId", postId);
		body.addProperty("reason", reason);
		body.addProperty("note", note);
		fireAndForget("/report", body.toString());
	}

	// Sets this device's star rating (value in half-stars 1..10, 0 clears) and returns the
	// authoritative { starAvg, starCount, myStars } so the UI can reflect the new average.
	public static @Nullable JsonObject rate(String postId, int value) {
		if (!configured()) {
			return null;
		}

		JsonObject body = new JsonObject();
		body.addProperty("postId", postId);
		body.addProperty("value", value);
		return postJsonForResult("/rate", body.toString());
	}

	private static @Nullable JsonObject postJsonForResult(String path, String jsonBody) {
		if (!configured()) {
			return null;
		}

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
					.timeout(Duration.ofSeconds(12))
					.header("Content-Type", "application/json")
					.header("X-Device-Token", Settings.deviceToken())
					.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() / 100 == 2) {
				return JsonParser.parseString(response.body()).getAsJsonObject();
			}
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("POST {} failed", path, e);
		}

		return null;
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

	public static boolean download(String url, Path target) {
		if (!configured()) {
			return false;
		}

		try {
			Files.createDirectories(target.getParent());
			Path temporary = target.resolveSibling(target.getFileName() + ".part");
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(60))
					.header("User-Agent", "SchematicIndex")
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
			SchematicIndexMod.LOGGER.debug("download {} failed", url, e);
			return false;
		}
	}

	public static @Nullable JsonObject myStats(String code, int days) {
		if (!configured()) {
			return null;
		}

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/me/stats?days=" + days))
					.timeout(Duration.ofSeconds(12))
					.header("X-Upload-Code", code)
					.GET()
					.build();
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 400) {
				return null;
			}

			return JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("me/stats failed", e);
			return null;
		}
	}

	public static @Nullable JsonObject uploaderInfo(String code) {
		if (!configured()) {
			return null;
		}

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
			return body.has("valid") && body.get("valid").getAsBoolean() ? body : null;
		} catch (Throwable e) {
			return null;
		}
	}

	public static @Nullable String checkCode(String code) {
		JsonObject body = uploaderInfo(code);
		return body != null ? str(body, "displayName") : null;
	}

	// Shares a collection (name + posts) and returns a short code others can load.
	public static @Nullable String shareCollection(String name, List<String> postIds) {
		if (!configured()) {
			return null;
		}

		JsonArray ids = new JsonArray();

		for (String id : postIds) {
			ids.add(id);
		}

		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		body.add("postIds", ids);
		JsonObject result = postJsonForResult("/collections/share", body.toString());
		return result != null && result.has("code") ? result.get("code").getAsString() : null;
	}

	// Resolves a shared collection code to its { name, postIds }, or null if unknown.
	public static @Nullable JsonObject loadCollectionCode(String code) {
		JsonObject data = getJson("/collections/" + encode(code));

		if (data == null || !data.has("postIds") || !data.get("postIds").isJsonArray()) {
			return null;
		}

		return data;
	}

	// Recent follows and likes for the signed-in uploader (newest first).
	public static @Nullable JsonObject notifications(String code) {
		if (!configured()) {
			return null;
		}

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/me/notifications"))
					.timeout(Duration.ofSeconds(10))
					.header("X-Upload-Code", code)
					.GET()
					.build();
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 400) {
				return null;
			}

			return JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("me/notifications failed", e);
			return null;
		}
	}

	public static int editPost(String code, String postId, String title, String thumbnailName, String designer,
			String description, String category) {
		JsonObject body = new JsonObject();
		body.addProperty("title", title);
		body.addProperty("thumbnailName", thumbnailName);
		body.addProperty("designer", designer);
		body.addProperty("description", description);
		body.addProperty("category", category);
		return postWithCode("/me/posts/" + encode(postId) + "/edit", code, body.toString());
	}

	public static int unpublishPost(String code, String postId) {
		return postWithCode("/me/posts/" + encode(postId) + "/unpublish", code, "{}");
	}

	private static int postWithCode(String path, String code, String jsonBody) {
		if (!configured()) {
			return -1;
		}

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
					.timeout(Duration.ofSeconds(12))
					.header("Content-Type", "application/json")
					.header("X-Upload-Code", code)
					.header("X-Device-Token", Settings.deviceToken())
					.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
					.build();
			return CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("POST {} failed", path, e);
			return -1;
		}
	}

	public record UploadResult(int status, @Nullable String message) {
	}

	private static volatile double uploadFraction;

	public static double uploadFraction() {
		return uploadFraction;
	}

	public static UploadResult upload(String code, String metaJson, Path schematic, List<Path> images) {
		if (!configured()) {
			return new UploadResult(-1, null);
		}

		try {
			uploadFraction = 0.0;
			String boundary = "----schematicindex" + System.nanoTime();
			byte[] body = multipart(boundary, metaJson, schematic, images);
			long total = body.length;
			HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/upload"))
					.timeout(Duration.ofMinutes(10))
					.header("X-Upload-Code", code)
					.header("X-Device-Token", Settings.deviceToken())
					.header("Content-Type", "multipart/form-data; boundary=" + boundary)
					.POST(HttpRequest.BodyPublishers.ofInputStream(() -> new CountingStream(new ByteArrayInputStream(body), total)))
					.build();
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			uploadFraction = 1.0;
			return new UploadResult(response.statusCode(), messageOf(response.body()));
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.warn("Upload failed", e);
			return new UploadResult(-1, null);
		}
	}

	private static final class CountingStream extends FilterInputStream {
		private final long total;
		private long count;

		CountingStream(InputStream in, long total) {
			super(in);
			this.total = total;
		}

		@Override
		public int read() throws IOException {
			int value = super.read();

			if (value >= 0) {
				count++;
				update();
			}

			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int read = super.read(buffer, offset, length);

			if (read > 0) {
				count += read;
				update();
			}

			return read;
		}

		private void update() {
			uploadFraction = total > 0 ? Math.min(1.0, (double) count / total) : 0.0;
		}
	}

	private static @Nullable String messageOf(String body) {
		try {
			JsonObject object = JsonParser.parseString(body).getAsJsonObject();
			return object.has("message") && !object.get("message").isJsonNull()
					? object.get("message").getAsString()
					: null;
		} catch (Throwable e) {
			return null;
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
				boolOf(o, "liked"), doubleOf(o, "trendScore"), intOf(o, "views"),
				doubleOf(o, "starAvg"), intOf(o, "starCount"), intOf(o, "myStars"));
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

	private static double doubleOf(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0.0;
	}

	private static boolean boolOf(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
	}
}
