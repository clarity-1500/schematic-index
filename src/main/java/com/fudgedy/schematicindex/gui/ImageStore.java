package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

/**
 * Supplies gallery images.
 *
 * <p><b>Temporary:</b> images are read from the local Minecraft screenshots folder so the grid can be
 * tested with real pictures. The shipped mod will fetch these from the catalogue instead - the rest of
 * the GUI only ever asks for "image number N", so swapping the source does not touch the layout code.
 *
 * <p>This is also a dry run of the real pipeline: decode off-thread, scale to the planned 512x288
 * asset size, upload to the GPU on the render thread a couple per frame, and release every texture on
 * close so scrolling a large grid cannot leak GPU memory.
 */
public final class ImageStore {
	private static final int TARGET_WIDTH = 512;
	private static final int TARGET_HEIGHT = 288;
	private static final int MAX_IMAGES = 64;
	private static final int UPLOADS_PER_FRAME = 2;

	private static final List<Path> FILES = new ArrayList<>();
	/** Pictures chosen through the upload form live in their own index space so they never shift. */
	private static final int PICKED_BASE = 1_000_000;
	private static final List<Path> PICKED = new ArrayList<>();
	private static final Map<Integer, Identifier> READY = new HashMap<>();
	private static final Set<Integer> REQUESTED = new HashSet<>();
	private static final Queue<Decoded> PENDING = new ConcurrentLinkedQueue<>();

	private static boolean scanned;

	private record Decoded(int index, NativeImage image) {
	}

	private ImageStore() {
	}

	public static void discover() {
		if (scanned) {
			return;
		}

		scanned = true;
		Path directory = testImageDirectory();

		if (directory == null || !Files.isDirectory(directory)) {
			SchematicIndexMod.LOGGER.info("No test image folder at {} - cards will use the blueprint placeholder", directory);
			return;
		}

		try (Stream<Path> stream = Files.list(directory)) {
			stream.filter(path -> {
						String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
						return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
					})
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.limit(MAX_IMAGES)
					.forEach(FILES::add);
		} catch (IOException e) {
			SchematicIndexMod.LOGGER.warn("Could not list test images in {}", directory, e);
		}

		SchematicIndexMod.LOGGER.info("Loaded {} test images from {}", FILES.size(), directory);
	}

	private static @Nullable Path testImageDirectory() {
		String override = System.getProperty("schematicindex.testimages");

		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}

		String appData = System.getenv("APPDATA");

		if (appData == null || appData.isBlank()) {
			return null;
		}

		return Path.of(appData, "ModrinthApp", "profiles", "Fabulously Optimized", "screenshots");
	}

	public static int count() {
		return FILES.size();
	}

	/** @return the index of the first registered picture; the rest follow consecutively */
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

	/**
	 * Returns the texture for an image slot, kicking off a background decode the first time it is
	 * asked for. Returns null until the upload lands, so callers should fall back to the placeholder.
	 */
	public static @Nullable Identifier texture(int index) {
		int slot = index >= PICKED_BASE ? index : (FILES.isEmpty() ? -1 : Math.floorMod(index, FILES.size()));

		if (slot < 0 || fileFor(slot) == null) {
			return null;
		}

		Identifier ready = READY.get(slot);

		if (ready != null) {
			return ready;
		}

		if (REQUESTED.add(slot)) {
			CompletableFuture.runAsync(() -> decode(slot));
		}

		return null;
	}

	private static void decode(int slot) {
		Path file = fileFor(slot);

		if (file == null) {
			return;
		}

		try (InputStream input = Files.newInputStream(file)) {
			NativeImage source = NativeImage.read(input);
			NativeImage target = new NativeImage(source.format(), TARGET_WIDTH, TARGET_HEIGHT, false);

			// Centre-crop to 16:9 before scaling, so nothing is squashed.
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
			PENDING.add(new Decoded(slot, target));
		} catch (Exception e) {
			SchematicIndexMod.LOGGER.warn("Could not decode test image {}", file, e);
		}
	}

	/** Must run on the render thread - texture upload touches the GPU. */
	public static void uploadPending() {
		Minecraft client = Minecraft.getInstance();

		for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
			Decoded decoded = PENDING.poll();

			if (decoded == null) {
				return;
			}

			Identifier id = Identifier.fromNamespaceAndPath(SchematicIndexMod.MOD_ID, "test/" + decoded.index());
			client.getTextureManager().register(id, new DynamicTexture(() -> "schematicindex-" + decoded.index(), decoded.image()));
			READY.put(decoded.index(), id);
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
}
