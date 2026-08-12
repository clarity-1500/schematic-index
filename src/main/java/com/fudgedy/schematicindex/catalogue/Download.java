package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.fudgedy.schematicindex.Settings;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Download {
	public enum State {
		RUNNING,
		DONE,
		FAILED
	}

	public record Progress(State state, float fraction, @Nullable String message) {
	}

	private static final Map<String, Progress> BY_POST = new ConcurrentHashMap<>();

	private Download() {
	}

	public static @Nullable Progress progress(String postId) {
		return BY_POST.get(postId);
	}

	public static void forget(String postId) {
		BY_POST.remove(postId);
	}

	public static Path resolveTarget(String fileName) {
		return Settings.downloadDirectory().resolve(safeName(fileName));
	}

	public static void start(String postId, String fileName, @Nullable String url, @Nullable Path source) {
		Progress current = BY_POST.get(postId);

		if (current != null && current.state() == State.RUNNING) {
			return;
		}

		BY_POST.put(postId, new Progress(State.RUNNING, 0.0F, null));

		Thread worker = new Thread(() -> {
			try {
				Path target = Settings.downloadDirectory().resolve(safeName(fileName));
				Files.createDirectories(target.getParent());
				Path temporary = target.resolveSibling(target.getFileName() + ".part");
				long total = url != null && !url.isBlank()
						? expectedLength(url)
						: (source == null ? -1L : Files.size(source));

				try (InputStream input = open(url, source, postId);
						OutputStream output = Files.newOutputStream(temporary)) {
					copy(postId, input, output, total);
				}

				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
				BY_POST.put(postId, new Progress(State.DONE, 1.0F, target.getFileName().toString()));
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.warn("Download failed for {}", postId, e);
				BY_POST.put(postId, new Progress(State.FAILED, 0.0F, e.getClass().getSimpleName()));
			}
		}, "schematicindex-download");
		worker.setDaemon(true);
		worker.start();
	}

	private static InputStream open(@Nullable String url, @Nullable Path source, String postId) throws Exception {
		if (url != null && !url.isBlank()) {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).build();
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() >= 400) {
				throw new IllegalStateException("HTTP " + response.statusCode());
			}

			return response.body();
		}

		if (source == null) {
			throw new IllegalStateException("Nothing to download for " + postId);
		}

		return Files.newInputStream(source);
	}

	private static long expectedLength(String url) {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.method("HEAD", HttpRequest.BodyPublishers.noBody())
					.build();
			return client.send(request, HttpResponse.BodyHandlers.discarding())
					.headers().firstValueAsLong("content-length").orElse(-1L);
		} catch (Throwable e) {
			return -1L;
		}
	}

	private static void copy(String postId, InputStream input, OutputStream output, long total) throws Exception {
		byte[] buffer = new byte[16 * 1024];
		long written = 0L;
		int read;

		while ((read = input.read(buffer)) > 0) {
			output.write(buffer, 0, read);
			written += read;

			float fraction = total > 0
					? Math.min(1.0F, (float) written / total)
					: 1.0F - 1.0F / (1.0F + written / 65_536.0F);
			BY_POST.put(postId, new Progress(State.RUNNING, fraction, null));
		}
	}

	private static String safeName(String fileName) {
		String cleaned = fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();

		if (cleaned.isBlank()) {
			cleaned = "schematic";
		}

		return cleaned.endsWith(".litematic") ? cleaned : cleaned + ".litematic";
	}
}
