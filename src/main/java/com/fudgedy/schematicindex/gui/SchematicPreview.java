package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.fudgedy.schematicindex.catalogue.Backend;
import com.mojang.blaze3d.platform.NativeImage;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.util.FileType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class SchematicPreview {
	public static final int WIDTH = 480;
	public static final int HEIGHT = 270;

	public static final float MIN_ZOOM = 0.8F;

	private static final int MAX_VOXELS = 6_000_000;

	private static final int MAX_CACHED_MODELS = 5;
	private static final int BACKGROUND = 0xFF10151A;
	private static final double FIELD_OF_VIEW = 70.0D;

	private static final int WATER_R = (BlockTextures.WATER_TINT >> 16) & 0xFF;
	private static final int WATER_G = (BlockTextures.WATER_TINT >> 8) & 0xFF;
	private static final int WATER_B = BlockTextures.WATER_TINT & 0xFF;
	private static final double WATER_DENSITY = 0.55D;
	private static final double WATER_MAX_A = 0.82D;

	private static final List<Path> FILES = new ArrayList<>();

	private static final int PICKED_BASE = 1_000_000;
	private static final List<Path> PICKED = new ArrayList<>();

	private static final int URL_BASE = 2_000_000;
	private static final List<String> URLS = new ArrayList<>();
	private static final List<Path> URL_FILES = new ArrayList<>();
	private static final Map<String, Integer> URL_INDEX = new HashMap<>();

	private static final Map<Integer, Model> MODELS = new ConcurrentHashMap<>();
	private static final Set<Integer> LOADING = new CopyOnWriteArraySet<>();
	private static final Set<Integer> FAILED = new CopyOnWriteArraySet<>();
	private static final Queue<Integer> MODEL_ORDER = new ConcurrentLinkedQueue<>();
	private static final Queue<Frame> PENDING = new ConcurrentLinkedQueue<>();

	private static final ExecutorService RENDERER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "schematicindex-preview");
		thread.setDaemon(true);
		return thread;
	});

	private static boolean scanned;

	private static @Nullable Identifier viewId;
	private static @Nullable DynamicTexture viewTexture;
	private static @Nullable NativeImage scratch;
	private static @Nullable View shown;
	private static @Nullable View queued;
	private static boolean rendering;

	private record View(int slot, float yaw, float pitch, float zoom, boolean cutaway, boolean freeLook,
			double eyeX, double eyeY, double eyeZ, float maxLayer, int quality) {
	}

	private record Frame(View view, @Nullable NativeImage image) {
	}

	private record Model(short[] cells, BlockShapes.Shape[] palette, int sizeX, int sizeY, int sizeZ,
			double radius, double fitScale) {
		int at(int x, int y, int z) {
			if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
				return 0;
			}

			return this.cells[(y * this.sizeZ + z) * this.sizeX + x] & 0xFFFF;
		}
	}

	private record Draft(short[] cells, List<BlockState> states, int sizeX, int sizeY, int sizeZ) {
	}

	private SchematicPreview() {
	}

	public static void discover() {
		scanned = true;
	}

	public static int count() {
		return FILES.size();
	}

	public static int layerHeight(int slot) {
		Model model = MODELS.get(normalise(slot));
		return model == null ? -1 : model.sizeY();
	}

	public static int register(Path file) {
		int slot = PICKED_BASE + PICKED.size();
		PICKED.add(file);
		return slot;
	}

	public static synchronized int registerUrl(String url) {
		Integer existing = URL_INDEX.get(url);

		if (existing != null) {
			return URL_BASE + existing;
		}

		int index = URLS.size();
		URLS.add(url);
		URL_FILES.add(null);
		URL_INDEX.put(url, index);
		return URL_BASE + index;
	}

	private static @Nullable Path fileFor(int slot) {
		if (slot >= URL_BASE) {
			int i = slot - URL_BASE;
			return i >= 0 && i < URL_FILES.size() ? URL_FILES.get(i) : null;
		}

		if (slot >= PICKED_BASE) {
			int picked = slot - PICKED_BASE;
			return picked < PICKED.size() ? PICKED.get(picked) : null;
		}

		return FILES.isEmpty() ? null : FILES.get(Math.floorMod(slot, FILES.size()));
	}

	public static @Nullable Path pathFor(int slot) {
		return fileFor(normalise(slot));
	}

	private static boolean hasSource(int index) {
		if (index >= URL_BASE) {
			int i = index - URL_BASE;
			return i >= 0 && i < URLS.size();
		}

		return fileFor(index) != null;
	}

	public static boolean hasSchematic(int slot) {
		int index = normalise(slot);
		return index >= 0 && hasSource(index);
	}

	public static boolean failed(int slot) {
		return FAILED.contains(normalise(slot));
	}

	public static boolean hosted(int slot) {
		return normalise(slot) >= URL_BASE;
	}

	public static void retry(int slot) {
		int index = normalise(slot);

		if (index >= 0) {
			FAILED.remove(index);
		}
	}

	private static int normalise(int slot) {
		if (slot >= PICKED_BASE) {
			return slot;
		}

		return FILES.isEmpty() ? -1 : Math.floorMod(slot, FILES.size());
	}

	public static String name(int slot) {
		if (slot >= URL_BASE) {
			int i = slot - URL_BASE;
			String url = i >= 0 && i < URLS.size() ? URLS.get(i) : "";
			String file = url.substring(url.lastIndexOf('/') + 1);
			return file.endsWith(".litematic") ? file.substring(0, file.length() - 10) : file;
		}

		Path source = fileFor(normalise(slot));

		if (source == null) {
			return "";
		}

		String file = source.getFileName().toString();
		return file.endsWith(".litematic") ? file.substring(0, file.length() - 10) : file;
	}

	private static @Nullable Path ensureLocal(int slot) {
		if (slot < URL_BASE) {
			return fileFor(slot);
		}

		int i = slot - URL_BASE;

		if (i < 0 || i >= URLS.size()) {
			return null;
		}

		Path cached = URL_FILES.get(i);

		if (cached != null && Files.exists(cached)) {
			return cached;
		}

		try {
			Path dir = FabricLoader.getInstance().getGameDir().resolve(SchematicIndexMod.MOD_ID).resolve("schcache");
			Files.createDirectories(dir);
			Path file = dir.resolve(Integer.toHexString(URLS.get(i).hashCode()) + ".litematic");

			if (!Backend.download(URLS.get(i), file)) {
				return null;
			}

			URL_FILES.set(i, file);
			return file;
		} catch (Exception e) {
			SchematicIndexMod.LOGGER.warn("Could not cache preview file", e);
			return null;
		}
	}

	public static float clampZoom(int slot, float zoom) {
		Model model = MODELS.get(normalise(slot));
		float max = model == null ? 12.0F : (float) (Math.min(WIDTH, HEIGHT) / 2.0D / model.fitScale());
		return Math.max(MIN_ZOOM, Math.min(Math.max(MIN_ZOOM, max), zoom));
	}

	public static double @Nullable [] eye(int slot, float yaw, float pitch, float zoom, boolean cutaway) {
		Model model = MODELS.get(normalise(slot));
		return model == null ? null : eye(model, slot, yaw, pitch, zoom, cutaway);
	}

	private static double[] eye(Model model, int slot, float yaw, float pitch, float zoom, boolean cutaway) {
		double radians = Math.toRadians(yaw);
		double tilt = Math.toRadians(pitch);
		double dirX = Math.sin(radians) * Math.cos(tilt);
		double dirY = -Math.sin(tilt);
		double dirZ = Math.cos(radians) * Math.cos(tilt);

		double distance = model.radius() * 2.0D / Math.max(0.05D, clampZoom(slot, zoom));

		if (!cutaway) {
			distance = Math.max(distance, model.radius() + 2.0D);
		}

		return new double[]{
				model.sizeX() / 2.0D - dirX * distance,
				model.sizeY() / 2.0D - dirY * distance,
				model.sizeZ() / 2.0D - dirZ * distance
		};
	}

	public static void request(int slot, float yaw, float pitch, float zoom, boolean cutaway,
			boolean freeLook, double @Nullable [] eye, float maxLayer, boolean interacting) {
		int index = normalise(slot);

		if (index < 0 || !hasSource(index)) {
			return;
		}

		if (!MODELS.containsKey(index)) {
			load(index);
			return;
		}

		double[] from = eye != null ? eye
				: (freeLook ? eye(MODELS.get(index), index, yaw, pitch, zoom, cutaway) : null);

		// While the user is actively orbiting/dragging, trace at half resolution (a quarter of the
		// rays) for a responsive image; a crisp full-resolution frame is rendered once they settle.
		int quality = interacting ? 2 : 1;

		View wanted = new View(index, yaw, pitch, clampZoom(index, zoom), cutaway, freeLook,
				from == null ? 0.0D : from[0], from == null ? 0.0D : from[1], from == null ? 0.0D : from[2],
				Math.max(0.0F, Math.min(1.0F, maxLayer)), quality);

		if (wanted.equals(queued) || (!rendering && wanted.equals(shown))) {
			return;
		}

		queued = wanted;
		startNext();
	}

	private static void startNext() {
		if (rendering || queued == null) {
			return;
		}

		View view = queued;
		Model model = MODELS.get(view.slot());

		if (model == null) {
			return;
		}

		queued = null;
		rendering = true;
		NativeImage buffer = scratch;
		scratch = null;
		RENDERER.execute(() -> {
			try {
				PENDING.add(new Frame(view, rasterise(model, view, buffer)));
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.warn("Preview render failed", e);

				if (buffer != null) {
					buffer.close();
				}

				PENDING.add(new Frame(view, null));
			}
		});
	}

	private static void load(int index) {
		if (FAILED.contains(index) || !LOADING.add(index)) {
			return;
		}

		CompletableFuture.supplyAsync(() -> ensureLocal(index)).thenAccept(file -> {
		if (file == null) {
			FAILED.add(index);
			LOADING.remove(index);
			return;
		}

		Minecraft.getInstance().submit(() -> {
			try {
				return LitematicaSchematic.createFromFile(
						file.getParent(), file.getFileName().toString(), FileType.LITEMATICA_SCHEMATIC);
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.warn("Could not read {}", file.getFileName(), e);
				return null;
			}
		}).thenApplyAsync(schematic -> schematic == null ? null : collect(schematic)
		).thenApplyAsync(draft -> {
			if (draft == null) {
				return null;
			}

			Object[] baked = Minecraft.getInstance().submit(() -> {
				int count = draft.states().size();
				BlockTextures.Resolved[] sprites = new BlockTextures.Resolved[count];
				BlockShapes.Raw[] shapes = new BlockShapes.Raw[count];

				for (int i = 0; i < count; i++) {
					BlockState state = draft.states().get(i);
					sprites[i] = BlockTextures.resolveSprites(state);
					shapes[i] = BlockShapes.extract(state);
				}

				return new Object[]{sprites, shapes};
			}).join();

			return new Object[]{draft, baked[0], baked[1]};
		}).thenAcceptAsync(pair -> {
			try {
				if (pair != null) {
					MODELS.put(index, finish((Draft) pair[0], (BlockTextures.Resolved[]) pair[1],
							(BlockShapes.Raw[]) pair[2]));
					MODEL_ORDER.remove(index);
					MODEL_ORDER.add(index);
					evictModels(index);
				} else {
					FAILED.add(index);
				}
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.warn("Could not build model for {}", file.getFileName(), e);
				FAILED.add(index);
			} finally {
				LOADING.remove(index);
			}
		});
		});
	}

	private static void evictModels(int keep) {
		while (MODELS.size() > MAX_CACHED_MODELS) {
			Integer oldest = MODEL_ORDER.poll();

			if (oldest == null) {
				break;
			}

			if (oldest == keep) {
				MODEL_ORDER.add(oldest);
				continue;
			}

			MODELS.remove(oldest);
		}
	}

	private static @Nullable Draft collect(LitematicaSchematic schematic) {
		Map<String, BlockPos> sizes = schematic.getAreaSizes();

		if (sizes.isEmpty()) {
			return null;
		}

		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (Map.Entry<String, BlockPos> region : sizes.entrySet()) {
			BlockPos origin = schematic.getSubRegionPosition(region.getKey());

			if (origin == null) {
				continue;
			}

			BlockPos corner = farCorner(origin, region.getValue());
			minX = Math.min(minX, Math.min(origin.getX(), corner.getX()));
			minY = Math.min(minY, Math.min(origin.getY(), corner.getY()));
			minZ = Math.min(minZ, Math.min(origin.getZ(), corner.getZ()));
			maxX = Math.max(maxX, Math.max(origin.getX(), corner.getX()));
			maxY = Math.max(maxY, Math.max(origin.getY(), corner.getY()));
			maxZ = Math.max(maxZ, Math.max(origin.getZ(), corner.getZ()));
		}

		if (minX > maxX) {
			return null;
		}

		int spanX = maxX - minX + 1;
		int spanY = maxY - minY + 1;
		int spanZ = maxZ - minZ + 1;

		int step = 1;

		while ((long) (spanX / step) * (spanY / step) * (spanZ / step) > MAX_VOXELS) {
			step++;
		}

		int sizeX = Math.max(1, spanX / step);
		int sizeY = Math.max(1, spanY / step);
		int sizeZ = Math.max(1, spanZ / step);
		short[] cells = new short[sizeX * sizeY * sizeZ];

		List<BlockState> states = new ArrayList<>();
		Map<BlockState, Integer> indices = new HashMap<>();

		for (Map.Entry<String, BlockPos> region : sizes.entrySet()) {
			String name = region.getKey();
			BlockPos origin = schematic.getSubRegionPosition(name);
			LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);

			if (origin == null || container == null) {
				continue;
			}

			BlockPos corner = farCorner(origin, region.getValue());
			int baseX = Math.min(origin.getX(), corner.getX());
			int baseY = Math.min(origin.getY(), corner.getY());
			int baseZ = Math.min(origin.getZ(), corner.getZ());

			Vec3i containerSize = container.getSize();

			for (int cx = 0; cx < containerSize.getX(); cx++) {
				for (int cy = 0; cy < containerSize.getY(); cy++) {
					for (int cz = 0; cz < containerSize.getZ(); cz++) {
						BlockState state = container.get(cx, cy, cz);

						if (state == null || state.isAir()) {
							continue;
						}

						int gx = (baseX + cx - minX) / step;
						int gy = (baseY + cy - minY) / step;
						int gz = (baseZ + cz - minZ) / step;

						if (gx < 0 || gy < 0 || gz < 0 || gx >= sizeX || gy >= sizeY || gz >= sizeZ) {
							continue;
						}

						Integer palette = indices.get(state);

						if (palette == null) {
							palette = states.size();
							indices.put(state, palette);
							states.add(state);
						}

						cells[(gy * sizeZ + gz) * sizeX + gx] = (short) (palette + 1);
					}
				}
			}
		}

		return new Draft(cells, states, sizeX, sizeY, sizeZ);
	}

	private static BlockPos farCorner(BlockPos origin, Vec3i size) {
		return new BlockPos(
				origin.getX() + size.getX() - (size.getX() < 0 ? -1 : 1),
				origin.getY() + size.getY() - (size.getY() < 0 ? -1 : 1),
				origin.getZ() + size.getZ() - (size.getZ() < 0 ? -1 : 1));
	}

	private static Model finish(Draft draft, BlockTextures.Resolved[] sprites, BlockShapes.Raw[] shapes) {
		BlockShapes.Shape[] palette = new BlockShapes.Shape[draft.states().size()];

		for (int i = 0; i < palette.length; i++) {
			BlockTextures.Faces faces = BlockTextures.load(sprites[i], draft.states().get(i));
			palette[i] = BlockShapes.build(shapes[i], faces);
		}

		double radius = 0.5D * Math.sqrt((double) draft.sizeX() * draft.sizeX()
				+ (double) draft.sizeY() * draft.sizeY()
				+ (double) draft.sizeZ() * draft.sizeZ());
		double fitScale = Math.min(WIDTH, HEIGHT) / (2.0D * Math.max(1.0D, radius)) * 0.95D;
		return new Model(draft.cells(), palette, draft.sizeX(), draft.sizeY(), draft.sizeZ(), radius, fitScale);
	}

	private static NativeImage rasterise(Model model, View view, @Nullable NativeImage buffer) {
		NativeImage image = buffer != null && buffer.getWidth() == WIDTH && buffer.getHeight() == HEIGHT
				? buffer
				: new NativeImage(NativeImage.Format.RGBA, WIDTH, HEIGHT, false);

		double yaw = Math.toRadians(view.yaw());
		double pitch = Math.toRadians(view.pitch());
		double sinYaw = Math.sin(yaw);
		double cosYaw = Math.cos(yaw);
		double sinPitch = Math.sin(pitch);
		double cosPitch = Math.cos(pitch);

		double dirX = sinYaw * cosPitch;
		double dirY = -sinPitch;
		double dirZ = cosYaw * cosPitch;
		double rightX = cosYaw;
		double rightZ = -sinYaw;
		double upX = sinPitch * sinYaw;
		double upY = cosPitch;
		double upZ = sinPitch * cosYaw;

		double scale = model.fitScale() * view.zoom();

		int lod = lodFor(scale);

		double distance = model.radius() * 2.0D / Math.max(0.05D, view.zoom());

		if (!view.cutaway() && !view.freeLook()) {
			distance = Math.max(distance, model.radius() + 2.0D);
		}
		double centreX = model.sizeX() / 2.0D;
		double centreY = model.sizeY() / 2.0D;
		double centreZ = model.sizeZ() / 2.0D;

		double planeDistance = view.cutaway() && !view.freeLook()
				? centreX * dirX + centreY * dirY + centreZ * dirZ - distance
				: Double.NEGATIVE_INFINITY;

		int layerCeiling = view.maxLayer() >= 1.0F
				? model.sizeY()
				: Math.max(1, Math.round(view.maxLayer() * model.sizeY()));

		// The per-pixel work is independent, so trace row-blocks across every CPU core (this is a
		// software raytracer - the GPU is not involved). `step` shrinks the ray count while orbiting.
		int step = Math.max(1, view.quality());
		int rowBlocks = (HEIGHT + step - 1) / step;

		final double fDirX = dirX, fDirY = dirY, fDirZ = dirZ;
		final double fRightX = rightX, fRightZ = rightZ;
		final double fUpX = upX, fUpY = upY, fUpZ = upZ;
		final double fScale = scale, fDistance = distance;
		final double fCentreX = centreX, fCentreY = centreY, fCentreZ = centreZ;
		final double fPlaneDistance = planeDistance;
		final int fLayerCeiling = layerCeiling, fLod = lod, fStep = step;
		final NativeImage target = image;

		if (view.freeLook()) {
			final double focal = 1.0D / Math.tan(Math.toRadians(FIELD_OF_VIEW) / 2.0D);
			final double halfHeight = HEIGHT / 2.0D;
			final double eyeX = view.eyeX(), eyeY = view.eyeY(), eyeZ = view.eyeZ();

			IntStream.range(0, rowBlocks).parallel().forEach(rb -> {
				int py = rb * fStep;
				double screenY = (halfHeight - py - 0.5D) / halfHeight;
				ShapeTracer.Hit hit = new ShapeTracer.Hit();

				for (int px = 0; px < WIDTH; px += fStep) {
					double screenX = (px + 0.5D - WIDTH / 2.0D) / halfHeight;

					double rayX = fDirX * focal + fRightX * screenX + fUpX * screenY;
					double rayY = fDirY * focal + fUpY * screenY;
					double rayZ = fDirZ * focal + fRightZ * screenX + fUpZ * screenY;
					double length = Math.sqrt(rayX * rayX + rayY * rayY + rayZ * rayZ);

					int color = trace(model, eyeX, eyeY, eyeZ, rayX / length, rayY / length, rayZ / length,
							fPlaneDistance, fLayerCeiling, fLod, hit);
					fillBlock(target, px, py, fStep, color);
				}
			});

			return image;
		}

		IntStream.range(0, rowBlocks).parallel().forEach(rb -> {
			int py = rb * fStep;
			double screenY = (HEIGHT / 2.0D - py - 0.5D) / fScale;
			ShapeTracer.Hit hit = new ShapeTracer.Hit();

			for (int px = 0; px < WIDTH; px += fStep) {
				double screenX = (px + 0.5D - WIDTH / 2.0D) / fScale;

				double originX = fCentreX + fRightX * screenX + fUpX * screenY - fDirX * fDistance;
				double originY = fCentreY + fUpY * screenY - fDirY * fDistance;
				double originZ = fCentreZ + fRightZ * screenX + fUpZ * screenY - fDirZ * fDistance;

				int color = trace(model, originX, originY, originZ, fDirX, fDirY, fDirZ,
						fPlaneDistance, fLayerCeiling, fLod, hit);
				fillBlock(target, px, py, fStep, color);
			}
		});

		return image;
	}

	// Writes a traced colour to a step x step block of pixels (step is 1 at full resolution).
	private static void fillBlock(NativeImage image, int px, int py, int step, int color) {
		if (step == 1) {
			image.setPixel(px, py, color);
			return;
		}

		int maxX = Math.min(WIDTH, px + step);
		int maxY = Math.min(HEIGHT, py + step);

		for (int yy = py; yy < maxY; yy++) {
			for (int xx = px; xx < maxX; xx++) {
				image.setPixel(xx, yy, color);
			}
		}
	}

	private static int trace(Model model, double originX, double originY, double originZ,
			double dirX, double dirY, double dirZ, double planeDistance, int layerCeiling, int lod,
			ShapeTracer.Hit hit) {
		double accR = 0.0D;
		double accG = 0.0D;
		double accB = 0.0D;
		double trans = 1.0D;
		boolean firstWater = true;

		double near = 0.0D;
		double far = Double.MAX_VALUE;
		int entryAxis = -1;

		for (int axis = 0; axis < 3; axis++) {
			double origin = axis == 0 ? originX : axis == 1 ? originY : originZ;
			double direction = axis == 0 ? dirX : axis == 1 ? dirY : dirZ;
			int bound = axis == 0 ? model.sizeX() : axis == 1 ? model.sizeY() : model.sizeZ();

			if (Math.abs(direction) < 1.0E-9D) {
				if (origin < 0.0D || origin > bound) {
					return composite(accR, accG, accB, trans, BACKGROUND);
				}

				continue;
			}

			double first = (0.0D - origin) / direction;
			double second = (bound - origin) / direction;

			if (first > second) {
				double swap = first;
				first = second;
				second = swap;
			}

			if (first > near) {
				near = first;
				entryAxis = axis;
			}

			far = Math.min(far, second);
		}

		if (near > far || far < 0.0D) {
			return composite(accR, accG, accB, trans, BACKGROUND);
		}

		double travelled = Math.max(near, 0.0D) + 1.0E-4D;
		double pointX = originX + dirX * travelled;
		double pointY = originY + dirY * travelled;
		double pointZ = originZ + dirZ * travelled;

		int x = clamp((int) Math.floor(pointX), model.sizeX());
		int y = clamp((int) Math.floor(pointY), model.sizeY());
		int z = clamp((int) Math.floor(pointZ), model.sizeZ());

		int stepX = dirX > 0 ? 1 : -1;
		int stepY = dirY > 0 ? 1 : -1;
		int stepZ = dirZ > 0 ? 1 : -1;

		double deltaX = Math.abs(1.0D / dirX);
		double deltaY = Math.abs(1.0D / dirY);
		double deltaZ = Math.abs(1.0D / dirZ);

		double nextX = travelled + boundary(pointX, x, dirX, stepX);
		double nextY = travelled + boundary(pointY, y, dirY, stepY);
		double nextZ = travelled + boundary(pointZ, z, dirZ, stepZ);

		int face = switch (entryAxis) {
			case 0 -> dirX > 0 ? Face.WEST : Face.EAST;
			case 1 -> dirY > 0 ? Face.DOWN : Face.UP;
			case 2 -> dirZ > 0 ? Face.NORTH : Face.SOUTH;

			default -> dirY < 0 ? Face.UP : Face.DOWN;
		};
		int guard = (model.sizeX() + model.sizeY() + model.sizeZ()) * 3;

		int insideX = (int) Math.floor(originX);
		int insideY = (int) Math.floor(originY);
		int insideZ = (int) Math.floor(originZ);

		for (int i = 0; i < guard; i++) {
			int cell = (y >= layerCeiling || (x == insideX && y == insideY && z == insideZ))
					? 0 : model.at(x, y, z);

			if (cell != 0) {
				BlockShapes.Shape shape = model.palette()[cell - 1];

				if (travelled < 2.0D && behindCamera(x, y, z, dirX, dirY, dirZ, planeDistance)) {
					shape = null;
				}

				if (shape != null && shape.invisible()) {
					shape = null;
				}

				if (shape != null && shape.waterVolume()) {
					double exit = Math.min(nextX, Math.min(nextY, nextZ));
					double added = addWater(exit - travelled, firstWater);
					accR += trans * added * WATER_R;
					accG += trans * added * WATER_G;
					accB += trans * added * WATER_B;
					trans *= 1.0D - added;
					firstWater = false;

					if (trans < 0.02D) {
						return composite(accR, accG, accB, trans, BACKGROUND);
					}

					shape = null;
				}

				if (shape != null) {
					if (shape.waterlogged()) {
						double exit = Math.min(nextX, Math.min(nextY, nextZ));
						double added = addWater((exit - travelled) * 0.6D, firstWater);
						accR += trans * added * WATER_R;
						accG += trans * added * WATER_G;
						accB += trans * added * WATER_B;
						trans *= 1.0D - added;
						firstWater = false;
					}

					if (shape.fullCube()) {
						double hitX = originX + dirX * travelled;
						double hitY = originY + dirY * travelled;
						double hitZ = originZ + dirZ * travelled;
						return composite(accR, accG, accB, trans, shade(shape.faces(), face, hitX, hitY, hitZ, lod));
					}

					if (ShapeTracer.trace(shape, originX - x, originY - y, originZ - z, dirX, dirY, dirZ, lod, hit)) {
						return composite(accR, accG, accB, trans, light(hit.color, hit.face));
					}
				}
			}

			if (nextX < nextY && nextX < nextZ) {
				travelled = nextX;
				x += stepX;
				nextX += deltaX;
				face = stepX > 0 ? Face.WEST : Face.EAST;

				if (x < 0 || x >= model.sizeX()) {
					return composite(accR, accG, accB, trans, BACKGROUND);
				}
			} else if (nextY < nextZ) {
				travelled = nextY;
				y += stepY;
				nextY += deltaY;
				face = stepY > 0 ? Face.DOWN : Face.UP;

				if (y < 0 || y >= model.sizeY()) {
					return composite(accR, accG, accB, trans, BACKGROUND);
				}
			} else {
				travelled = nextZ;
				z += stepZ;
				nextZ += deltaZ;
				face = stepZ > 0 ? Face.NORTH : Face.SOUTH;

				if (z < 0 || z >= model.sizeZ()) {
					return composite(accR, accG, accB, trans, BACKGROUND);
				}
			}
		}

		return composite(accR, accG, accB, trans, BACKGROUND);
	}

	private static final class Face {
		private static final int DOWN = 0;
		private static final int UP = 1;
		private static final int NORTH = 2;
		private static final int SOUTH = 3;
		private static final int WEST = 4;
		private static final int EAST = 5;

		private Face() {
		}
	}

	private static boolean behindCamera(int x, int y, int z, double dirX, double dirY, double dirZ,
			double planeDistance) {
		double cornerX = dirX > 0 ? x : x + 1;
		double cornerY = dirY > 0 ? y : y + 1;
		double cornerZ = dirZ > 0 ? z : z + 1;
		return cornerX * dirX + cornerY * dirY + cornerZ * dirZ < planeDistance;
	}

	private static double boundary(double point, int cell, double direction, int step) {
		if (Math.abs(direction) < 1.0E-9D) {
			return Double.MAX_VALUE;
		}

		double edge = step > 0 ? cell + 1 : cell;
		return (edge - point) / direction;
	}

	private static int clamp(int value, int size) {
		return Math.max(0, Math.min(size - 1, value));
	}

	private static int shade(BlockTextures.Faces faces, int face, double hitX, double hitY, double hitZ, int lod) {
		double u;
		double v;

		switch (face) {
			case Face.UP, Face.DOWN -> {
				u = frac(hitX);
				v = frac(hitZ);
			}
			case Face.WEST, Face.EAST -> {
				u = frac(hitZ);
				v = 1.0D - frac(hitY);
			}
			default -> {
				u = frac(hitX);
				v = 1.0D - frac(hitY);
			}
		}

		return light(faces.sample(face, u, v, lod), face);
	}

	private static final double LOG2 = Math.log(2.0D);

	private static int lodFor(double pixelsPerBlock) {
		double texelsPerPixel = 16.0D / Math.max(1.0D, pixelsPerBlock);

		if (texelsPerPixel <= 1.0D) {
			return 0;
		}

		return Math.max(0, Math.min(4, (int) Math.round(Math.log(texelsPerPixel) / LOG2)));
	}

	private static int light(int color, int face) {
		double brightness = switch (face) {
			case Face.UP -> 1.0D;
			case Face.DOWN -> 0.5D;
			case Face.NORTH, Face.SOUTH -> 0.8D;
			case Face.WEST, Face.EAST -> 0.65D;

			default -> 0.9D;
		};

		int red = (int) Math.min(255.0D, ((color >> 16) & 0xFF) * brightness);
		int green = (int) Math.min(255.0D, ((color >> 8) & 0xFF) * brightness);
		int blue = (int) Math.min(255.0D, (color & 0xFF) * brightness);
		return 0xFF000000 | (red << 16) | (green << 8) | blue;
	}

	private static double addWater(double thickness, boolean surface) {
		double a = Math.min(WATER_MAX_A, Math.max(0.0D, thickness) * WATER_DENSITY);
		return surface ? Math.min(WATER_MAX_A, a + 0.1D) : a;
	}

	private static int composite(double accR, double accG, double accB, double trans, int color) {
		if (trans >= 0.999D) {
			return color;
		}

		int red = (int) Math.min(255.0D, accR + trans * ((color >> 16) & 0xFF));
		int green = (int) Math.min(255.0D, accG + trans * ((color >> 8) & 0xFF));
		int blue = (int) Math.min(255.0D, accB + trans * (color & 0xFF));
		return 0xFF000000 | (red << 16) | (green << 8) | blue;
	}

	private static double frac(double value) {
		double fraction = value - Math.floor(value);
		return fraction < 0.0D ? fraction + 1.0D : fraction;
	}

	public static void uploadPending() {
		Frame frame = PENDING.poll();

		if (frame == null) {
			return;
		}

		rendering = false;

		if (frame.image() != null) {
			Minecraft client = Minecraft.getInstance();

			if (viewTexture == null) {
				viewId = Identifier.fromNamespaceAndPath(SchematicIndexMod.MOD_ID, "preview");
				viewTexture = new DynamicTexture(() -> "schematicindex-preview", frame.image());
				client.getTextureManager().register(viewId, viewTexture);
			} else {
				NativeImage previous = viewTexture.getPixels();
				viewTexture.setPixels(frame.image());
				viewTexture.upload();

				if (previous != null && previous != frame.image()) {
					if (scratch != null) {
						scratch.close();
					}

					scratch = previous;
				}
			}

			shown = frame.view();
		}

		startNext();
	}

	public static @Nullable Identifier texture(int slot) {
		if (shown == null) {
			return null;
		}

		return shown.slot() == normalise(slot) ? viewId : null;
	}

	public static void clearCache() {
		MODELS.clear();
		MODEL_ORDER.clear();
		LOADING.clear();
		FAILED.clear();
		BlockTextures.clear();
	}

	public static void releaseAll() {
		if (viewId != null) {
			Minecraft.getInstance().getTextureManager().release(viewId);
		}

		if (scratch != null) {
			scratch.close();
			scratch = null;
		}

		viewId = null;
		viewTexture = null;
		shown = null;
		queued = null;
		rendering = false;

		Frame pending;

		while ((pending = PENDING.poll()) != null) {
			if (pending.image() != null) {
				pending.image().close();
			}
		}
	}
}
