package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public final class ImageStore {
	private static final int TARGET_WIDTH = 512;
	private static final int TARGET_HEIGHT = 288;
	private static final int THUMB_WIDTH = 256;
	private static final int THUMB_HEIGHT = 144;
	private static final int MAX_IMAGES = 64;
	private static final int UPLOADS_PER_FRAME = 2;

	private static final List<Path> FILES = new ArrayList<>();

	private static final int PICKED_BASE = 1_000_000;
	private static final List<Path> PICKED = new ArrayList<>();

	private static final Map<String, Identifier> READY = new LinkedHashMap<String, Identifier>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
			if (size() > MAX_IMAGES) {
				Minecraft.getInstance().getTextureManager().release(eldest.getValue());
				return true;
			}

			return false;
		}
	};

	private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();
	private static final Queue<Decoded> PENDING = new ConcurrentLinkedQueue<>();

	private static final ExecutorService DECODER = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "schematicindex-image");
		thread.setDaemon(true);
		return thread;
	});

	private static int uploadCounter;

	private static boolean scanned;

	private record Decoded(String key, NativeImage image) {
	}

	@FunctionalInterface
	private interface ImageLoader {
		@Nullable
		NativeImage load() throws Exception;
	}

	private ImageStore() {
	}

	public static void discover() {
		scanned = true;
	}

	public static int count() {
		return FILES.size();
	}

	public static int register(List<Path> paths) {
		int start = PICKED_BASE + PICKED.size();
		PICKED.addAll(paths);
		return start;
	}

	private static @Nullable Path fileFor(int index) {
		if (index >= PICKED_BASE) {
			int picked = index - PICKED_BASE;
			return picked < PICKED.size() ? PICKED.get(picked) : null;
		}

		return FILES.isEmpty() ? null : FILES.get(Math.floorMod(index, FILES.size()));
	}

	public static @Nullable Identifier texture(int index) {
		return textureAt(index, TARGET_WIDTH, TARGET_HEIGHT, "f:");
	}

	public static @Nullable Identifier thumbnail(int index) {
		return textureAt(index, THUMB_WIDTH, THUMB_HEIGHT, "t:");
	}

	private static @Nullable Identifier textureAt(int index, int width, int height, String prefix) {
		int slot = index >= PICKED_BASE ? index : (FILES.isEmpty() ? -1 : Math.floorMod(index, FILES.size()));
		Path file = fileFor(slot);

		if (slot < 0 || file == null) {
			return null;
		}

		return request(prefix + "local:" + slot, () -> decodeStream(Files.newInputStream(file), width, height));
	}

	public static @Nullable Identifier texture(String ref) {
		return textureAt(ref, TARGET_WIDTH, TARGET_HEIGHT, "f:");
	}

	public static @Nullable Identifier thumbnail(String ref) {
		return textureAt(ref, THUMB_WIDTH, THUMB_HEIGHT, "t:");
	}

	private static @Nullable Identifier textureAt(String ref, int width, int height, String prefix) {
		if (ref == null || ref.isBlank()) {
			return null;
		}

		return request(prefix + ref, () -> loadReference(ref, width, height));
	}

	public static void preload(int start, int count) {
		for (int i = 0; i < count; i++) {
			texture(start + i);
		}
	}

	public static void preload(List<String> refs) {
		for (String ref : refs) {
			texture(ref);
		}
	}

	private static @Nullable Identifier request(String key, ImageLoader loader) {
		Identifier ready = READY.get(key);

		if (ready != null) {
			return ready;
		}

		if (REQUESTED.add(key)) {
			DECODER.execute(() -> {
				try {
					NativeImage image = loader.load();

					if (image != null) {
						PENDING.add(new Decoded(key, image));
					} else {
						REQUESTED.remove(key);
					}
				} catch (Exception e) {
					REQUESTED.remove(key);
					SchematicIndexMod.LOGGER.debug("Could not load image {}", key, e);
				}
			});
		}

		return null;
	}

	private static @Nullable NativeImage loadReference(String ref, int width, int height) throws Exception {
		byte[] bytes;

		if (ref.startsWith("http://") || ref.startsWith("https://")) {
			bytes = fetchCached(ref);
		} else {
			bytes = Files.readAllBytes(Path.of(ref));
		}

		return decodeStream(new ByteArrayInputStream(bytes), width, height);
	}

	private static NativeImage decodeStream(InputStream input, int targetWidth, int targetHeight) throws IOException {
		try (input) {
			NativeImage source = NativeImage.read(input);
			NativeImage target = new NativeImage(source.format(), targetWidth, targetHeight, false);

			int cropWidth = source.getWidth();
			int cropHeight = Math.round(cropWidth * 9.0F / 16.0F);

			if (cropHeight > source.getHeight()) {
				cropHeight = source.getHeight();
				cropWidth = Math.round(cropHeight * 16.0F / 9.0F);
			}

			int cropX = (source.getWidth() - cropWidth) / 2;
			int cropY = (source.getHeight() - cropHeight) / 2;

			source.resizeSubRectTo(cropX, cropY, cropWidth, cropHeight, target);
			source.close();
			return target;
		}
	}

	private static byte[] fetchCached(String url) throws Exception {
		Path cache = cacheFile(url);

		if (cache != null && Files.exists(cache)) {
			return Files.readAllBytes(cache);
		}

		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build();
		HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

		if (response.statusCode() >= 400) {
			throw new IOException("HTTP " + response.statusCode() + " for " + url);
		}

		byte[] body = response.body();

		if (cache != null) {
			try {
				Files.createDirectories(cache.getParent());
				Files.write(cache, body);
			} catch (IOException e) {
				SchematicIndexMod.LOGGER.debug("Could not cache image {}", url, e);
			}
		}

		return body;
	}

	private static @Nullable Path cacheFile(String url) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();

			for (int i = 0; i < 20; i++) {
				hex.append(String.format("%02x", hash[i]));
			}

			return cacheDirectory().resolve(hex + ".img");
		} catch (Exception e) {
			return null;
		}
	}

	private static Path cacheDirectory() {
		return FabricLoader.getInstance().getGameDir().resolve(SchematicIndexMod.MOD_ID).resolve("imagecache");
	}

	public static void uploadPending() {
		Minecraft client = Minecraft.getInstance();

		for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
			Decoded decoded = PENDING.poll();

			if (decoded == null) {
				return;
			}

			Identifier id = Identifier.fromNamespaceAndPath(SchematicIndexMod.MOD_ID, "cache/" + (uploadCounter++));
			client.getTextureManager().register(id, new DynamicTexture(() -> "schematicindex-image", decoded.image()));
			READY.put(decoded.key(), id);
		}
	}

	public static void releaseAll() {
		Minecraft client = Minecraft.getInstance();

		for (Identifier id : READY.values()) {
			client.getTextureManager().release(id);
		}

		READY.clear();
		REQUESTED.clear();

		Decoded pending;

		while ((pending = PENDING.poll()) != null) {
			pending.image().close();
		}
	}

	public static void clearDiskCache() {
		Path directory = cacheDirectory();

		if (!Files.isDirectory(directory)) {
			return;
		}

		try (Stream<Path> stream = Files.walk(directory)) {
			stream.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException e) {
			SchematicIndexMod.LOGGER.debug("Could not clear image cache", e);
		}
	}
}
