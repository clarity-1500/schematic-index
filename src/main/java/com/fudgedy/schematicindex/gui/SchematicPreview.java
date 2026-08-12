package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.mojang.blaze3d.platform.NativeImage;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.util.FileType;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Stream;

/**
 * Renders a schematic to an orbitable, textured voxel image.
 *
 * <p>Each pixel casts a ray into the voxel grid and samples the block texture where it lands. That
 * is slower per frame than stamping sprites, but it is exact at any camera angle, gives real block
 * textures rather than flat colours, and makes zoom a simple change of camera distance - close
 * enough and the camera passes through the walls, so enclosed builds can be inspected from inside.
 *
 * <p>Everything except the schematic parse and the model lookup runs off the render thread.
 *
 * <p><b>Temporary:</b> files come from the local Litematica schematics folder. The shipped mod will
 * render whatever it downloaded from the catalogue instead.
 */
public final class SchematicPreview {
	public static final int WIDTH = 480;
	public static final int HEIGHT = 270;

	public static final float MIN_ZOOM = 0.8F;

	private static final int MAX_VOXELS = 6_000_000;
	private static final int BACKGROUND = 0xFF10151A;
	private static final double FIELD_OF_VIEW = 70.0D;

	private static final List<Path> FILES = new ArrayList<>();
	/** Files chosen through the upload form live in their own index space so they never shift. */
	private static final int PICKED_BASE = 1_000_000;
	private static final List<Path> PICKED = new ArrayList<>();

	private static final Map<Integer, Model> MODELS = new ConcurrentHashMap<>();
	private static final Set<Integer> LOADING = new CopyOnWriteArraySet<>();
	private static final Queue<Frame> PENDING = new ConcurrentLinkedQueue<>();

	private static boolean scanned;

	private static @Nullable Identifier viewId;
	private static @Nullable DynamicTexture viewTexture;
	private static @Nullable View shown;
	private static @Nullable View queued;
	private static boolean rendering;

	/**
	 * Camera state for one frame.
	 *
	 * @param cutaway  drop blocks the camera plane cuts, so zooming in reveals interiors
	 * @param freeLook stand still and turn on the spot instead of orbiting the centre; the eye is
	 *                 frozen where the orbit camera was when the mode was switched on
	 */
	private record View(int slot, float yaw, float pitch, float zoom, boolean cutaway, boolean freeLook,
			double eyeX, double eyeY, double eyeZ) {
	}

	private record Frame(View view, @Nullable NativeImage image) {
	}

	/** Voxel grid of palette indices (0 = empty), plus the framing numbers derived from its size. */
	private record Model(int[] cells, BlockShapes.Shape[] palette, int sizeX, int sizeY, int sizeZ,
			double radius, double fitScale) {
		int at(int x, int y, int z) {
			if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
				return 0;
			}

			return this.cells[(y * this.sizeZ + z) * this.sizeX + x];
		}
	}

	/** Voxels plus the block states they reference, before textures have been resolved. */
	private record Draft(int[] cells, List<BlockState> states, int sizeX, int sizeY, int sizeZ) {
	}

	private SchematicPreview() {
	}

	// ------------------------------------------------------------------ files

	public static void discover() {
		if (scanned) {
			return;
		}

		scanned = true;
		Path directory = schematicDirectory();

		if (directory == null || !Files.isDirectory(directory)) {
			SchematicIndexMod.LOGGER.info("No schematic folder at {} - 3D preview will be unavailable", directory);
			return;
		}

		try (Stream<Path> stream = Files.walk(directory, 2)) {
			stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.limit(64)
					.forEach(FILES::add);
		} catch (Exception e) {
			SchematicIndexMod.LOGGER.warn("Could not list schematics in {}", directory, e);
		}

		SchematicIndexMod.LOGGER.info("Found {} schematics for 3D preview in {}", FILES.size(), directory);
	}

	private static @Nullable Path schematicDirectory() {
		String override = System.getProperty("schematicindex.testschematics");

		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}

		String appData = System.getenv("APPDATA");
		return appData == null || appData.isBlank()
				? null
				: Path.of(appData, "ModrinthApp", "profiles", "Fabulously Optimized", "schematics");
	}

	public static int count() {
		return FILES.size();
	}

	/**
	 * Registers a file chosen through the upload form and returns the slot that renders it. Kept in
	 * its own index space so it can never be confused with the local test schematics.
	 */
	public static int register(Path file) {
		int slot = PICKED_BASE + PICKED.size();
		PICKED.add(file);
		return slot;
	}

	private static @Nullable Path fileFor(int slot) {
		if (slot >= PICKED_BASE) {
			int picked = slot - PICKED_BASE;
			return picked < PICKED.size() ? PICKED.get(picked) : null;
		}

		return FILES.isEmpty() ? null : FILES.get(Math.floorMod(slot, FILES.size()));
	}

	private static int normalise(int slot) {
		if (slot >= PICKED_BASE) {
			return slot;
		}

		return FILES.isEmpty() ? -1 : Math.floorMod(slot, FILES.size());
	}

	public static String name(int slot) {
		Path source = fileFor(normalise(slot));

		if (source == null) {
			return "";
		}

		String file = source.getFileName().toString();
		return file.endsWith(".litematic") ? file.substring(0, file.length() - 10) : file;
	}

	/**
	 * Zoom 1.0 frames the whole build from outside. Higher values pull the camera in; past roughly 2
	 * it is inside the bounding box, which is how you get to look inside an enclosed build.
	 */
	public static float clampZoom(int slot, float zoom) {
		Model model = MODELS.get(normalise(slot));
		float max = model == null ? 12.0F : (float) (Math.min(WIDTH, HEIGHT) / 2.0D / model.fitScale());
		return Math.max(MIN_ZOOM, Math.min(Math.max(MIN_ZOOM, max), zoom));
	}

	/**
	 * Where the orbit camera currently sits. Free look freezes the eye here, so switching modes does
	 * not teleport the view.
	 */
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

		// Must match the distance the renderer actually used, clamp included - otherwise switching to
		// look-around drops the camera somewhere it never was, which reads as being teleported into
		// the middle of the build.
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

	// ------------------------------------------------------------------ requests

	/**
	 * Asks for a camera view. Renders happen one at a time and only the newest request is kept, so
	 * dragging never builds up a backlog of stale frames.
	 */
	public static void request(int slot, float yaw, float pitch, float zoom, boolean cutaway,
			boolean freeLook, double @Nullable [] eye) {
		int index = normalise(slot);

		if (index < 0 || fileFor(index) == null) {
			return;
		}

		if (!MODELS.containsKey(index)) {
			load(index);
			return;
		}

		// Never fall back to the origin corner: if the caller has no frozen eye yet, work out where
		// the orbit camera is right now.
		double[] from = eye != null ? eye
				: (freeLook ? eye(MODELS.get(index), index, yaw, pitch, zoom, cutaway) : null);

		View wanted = new View(index, yaw, pitch, clampZoom(index, zoom), cutaway, freeLook,
				from == null ? 0.0D : from[0], from == null ? 0.0D : from[1], from == null ? 0.0D : from[2]);

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
		Thread worker = new Thread(() -> {
			try {
				PENDING.add(new Frame(view, rasterise(model, view)));
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.warn("Preview render failed", e);
				PENDING.add(new Frame(view, null));
			}
		}, "schematicindex-preview");
		worker.setDaemon(true);
		worker.start();
	}

	private static void load(int index) {
		if (!LOADING.add(index)) {
			return;
		}

		Path file = fileFor(index);

		if (file == null) {
			LOADING.remove(index);
			return;
		}

		// The parse has to run on the render thread: Litematica reports progress through MaLiLib's
		// message system, which measures text, which bakes font glyphs into a GPU texture.
		Minecraft.getInstance().submit(() -> {
			try {
				// Not the public (Path, CompoundTag, FileType) constructor - it uses its converter
				// field before assigning it, so older schematics throw NullPointerException there.
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

			// Model lookup needs the block model registry, so hop back to the render thread for the
			// geometry and sprite names, then return to a worker to decode the PNGs.
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
				}
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.warn("Could not build model for {}", file.getFileName(), e);
			} finally {
				LOADING.remove(index);
			}
		});
	}

	// ------------------------------------------------------------------ model building

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

		// Very large builds get sampled down rather than refused.
		int step = 1;

		while ((long) (spanX / step) * (spanY / step) * (spanZ / step) > MAX_VOXELS) {
			step++;
		}

		int sizeX = Math.max(1, spanX / step);
		int sizeY = Math.max(1, spanY / step);
		int sizeZ = Math.max(1, spanZ / step);
		int[] cells = new int[sizeX * sizeY * sizeZ];

		List<BlockState> states = new ArrayList<>();
		Map<BlockState, Integer> indices = new HashMap<>();

		for (Map.Entry<String, BlockPos> region : sizes.entrySet()) {
			String name = region.getKey();
			BlockPos origin = schematic.getSubRegionPosition(name);
			LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);

			if (origin == null || container == null) {
				continue;
			}

			// Litematica always fills a region's container from its minimum corner, whatever sign the
			// stored size has. Walking outwards from the origin instead mirrors any region with a
			// negative axis - which is what made some builds render upside down.
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

						cells[(gy * sizeZ + gz) * sizeX + gx] = palette + 1;
					}
				}
			}
		}

		return new Draft(cells, states, sizeX, sizeY, sizeZ);
	}

	/** The corner opposite the region position, honouring Litematica's signed sizes. */
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

	// ------------------------------------------------------------------ raycasting

	private static NativeImage rasterise(Model model, View view) {
		NativeImage image = new NativeImage(NativeImage.Format.RGBA, WIDTH, HEIGHT, false);

		double yaw = Math.toRadians(view.yaw());
		double pitch = Math.toRadians(view.pitch());
		double sinYaw = Math.sin(yaw);
		double cosYaw = Math.cos(yaw);
		double sinPitch = Math.sin(pitch);
		double cosPitch = Math.cos(pitch);

		// Camera basis: D is the direction rays travel, R points right on screen, U points up.
		double dirX = sinYaw * cosPitch;
		double dirY = -sinPitch;
		double dirZ = cosYaw * cosPitch;
		double rightX = cosYaw;
		double rightZ = -sinYaw;
		double upX = sinPitch * sinYaw;
		double upY = cosPitch;
		double upZ = sinPitch * cosYaw;

		double scale = model.fitScale() * view.zoom();
		// Zoom doubles as a dolly: the closer the camera gets, the more of the near geometry falls
		// behind it, until it is inside the build looking out.
		double distance = model.radius() * 2.0D / Math.max(0.05D, view.zoom());

		if (!view.cutaway() && !view.freeLook()) {
			// With cutaway off the camera never enters the build, so zoom only magnifies and no
			// geometry is ever clipped or removed.
			distance = Math.max(distance, model.radius() + 2.0D);
		}
		double centreX = model.sizeX() / 2.0D;
		double centreY = model.sizeY() / 2.0D;
		double centreZ = model.sizeZ() / 2.0D;

		// One hit record per render, reused for every ray - this loop runs 129,600 times.
		ShapeTracer.Hit hit = new ShapeTracer.Hit();
		double planeDistance = view.cutaway() && !view.freeLook()
				? centreX * dirX + centreY * dirY + centreZ * dirZ - distance
				: Double.NEGATIVE_INFINITY;

		if (view.freeLook()) {
			// Standing still and turning on the spot wants a perspective camera - an orthographic
			// one just slides a slab of the world past you and reads as the build rotating.
			double focal = 1.0D / Math.tan(Math.toRadians(FIELD_OF_VIEW) / 2.0D);
			double halfHeight = HEIGHT / 2.0D;

			for (int py = 0; py < HEIGHT; py++) {
				double screenY = (halfHeight - py - 0.5D) / halfHeight;

				for (int px = 0; px < WIDTH; px++) {
					double screenX = (px + 0.5D - WIDTH / 2.0D) / halfHeight;

					double rayX = dirX * focal + rightX * screenX + upX * screenY;
					double rayY = dirY * focal + upY * screenY;
					double rayZ = dirZ * focal + rightZ * screenX + upZ * screenY;
					double length = Math.sqrt(rayX * rayX + rayY * rayY + rayZ * rayZ);

					image.setPixel(px, py, trace(model, view.eyeX(), view.eyeY(), view.eyeZ(),
							rayX / length, rayY / length, rayZ / length, planeDistance, hit));
				}
			}

			return image;
		}

		for (int py = 0; py < HEIGHT; py++) {
			double screenY = (HEIGHT / 2.0D - py - 0.5D) / scale;

			for (int px = 0; px < WIDTH; px++) {
				double screenX = (px + 0.5D - WIDTH / 2.0D) / scale;

				double originX = centreX + rightX * screenX + upX * screenY - dirX * distance;
				double originY = centreY + upY * screenY - dirY * distance;
				double originZ = centreZ + rightZ * screenX + upZ * screenY - dirZ * distance;

				image.setPixel(px, py,
						trace(model, originX, originY, originZ, dirX, dirY, dirZ, planeDistance, hit));
			}
		}

		return image;
	}

	/** Amanatides-Woo grid traversal: step voxel to voxel and shade the first solid one. */
	/**
	 * @param planeDistance where the camera plane sits along the view direction; blocks straddling
	 *                      it are culled whole so zooming in reveals the interior cleanly
	 */
	private static int trace(Model model, double originX, double originY, double originZ,
			double dirX, double dirY, double dirZ, double planeDistance, ShapeTracer.Hit hit) {
		double near = 0.0D;
		double far = Double.MAX_VALUE;
		int entryAxis = -1;

		double[] origins = {originX, originY, originZ};
		double[] directions = {dirX, dirY, dirZ};
		int[] bounds = {model.sizeX(), model.sizeY(), model.sizeZ()};

		for (int axis = 0; axis < 3; axis++) {
			if (Math.abs(directions[axis]) < 1.0E-9D) {
				if (origins[axis] < 0.0D || origins[axis] > bounds[axis]) {
					return BACKGROUND;
				}

				continue;
			}

			double first = (0.0D - origins[axis]) / directions[axis];
			double second = (bounds[axis] - origins[axis]) / directions[axis];

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
			return BACKGROUND;
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

		// The face the ray crossed to enter the grid. Without this the first voxel gets shaded and
		// UV-mapped as if it had been entered from below, which is why blocks on the outer skin of a
		// schematic came out with scrambled textures on their exposed side.
		int face = switch (entryAxis) {
			case 0 -> dirX > 0 ? Face.WEST : Face.EAST;
			case 1 -> dirY > 0 ? Face.DOWN : Face.UP;
			case 2 -> dirZ > 0 ? Face.NORTH : Face.SOUTH;
			// The camera is already inside the build, so there is no entry face to speak of.
			default -> dirY < 0 ? Face.UP : Face.DOWN;
		};
		int guard = (model.sizeX() + model.sizeY() + model.sizeZ()) * 3;

		// The block the camera is standing in never renders: there is no sensible face to shade and
		// no sensible texture coordinate, which is what produced the smeared mess up close.
		int insideX = (int) Math.floor(originX);
		int insideY = (int) Math.floor(originY);
		int insideZ = (int) Math.floor(originZ);

		for (int i = 0; i < guard; i++) {
			int cell = x == insideX && y == insideY && z == insideZ ? 0 : model.at(x, y, z);

			if (cell != 0) {
				BlockShapes.Shape shape = model.palette()[cell - 1];

				// Zooming in moves the camera plane through the build. Drop any block the plane
				// cuts, so blocks vanish whole and reveal the interior instead of being sliced in
				// half. Only worth testing near the plane.
				if (travelled < 2.0D && behindCamera(x, y, z, dirX, dirY, dirZ, planeDistance)) {
					shape = null;
				}

				if (shape != null && shape.invisible()) {
					shape = null;
				}

				if (shape != null && shape.fullCube()) {
					double hitX = originX + dirX * travelled;
					double hitY = originY + dirY * travelled;
					double hitZ = originZ + dirZ * travelled;
					return shade(shape.faces(), face, hitX, hitY, hitZ);
				}

				// Real model geometry: the ray may pass straight through the gaps around a torch or
				// under a slab, so a miss here just carries on to the next voxel.
				if (shape != null
						&& ShapeTracer.trace(shape, originX - x, originY - y, originZ - z, dirX, dirY, dirZ, hit)) {
					return light(hit.color, hit.face);
				}
			}

			if (nextX < nextY && nextX < nextZ) {
				travelled = nextX;
				x += stepX;
				nextX += deltaX;
				face = stepX > 0 ? Face.WEST : Face.EAST;

				if (x < 0 || x >= model.sizeX()) {
					return BACKGROUND;
				}
			} else if (nextY < nextZ) {
				travelled = nextY;
				y += stepY;
				nextY += deltaY;
				face = stepY > 0 ? Face.DOWN : Face.UP;

				if (y < 0 || y >= model.sizeY()) {
					return BACKGROUND;
				}
			} else {
				travelled = nextZ;
				z += stepZ;
				nextZ += deltaZ;
				face = stepZ > 0 ? Face.NORTH : Face.SOUTH;

				if (z < 0 || z >= model.sizeZ()) {
					return BACKGROUND;
				}
			}
		}

		return BACKGROUND;
	}

	/** Direction ordinals, matching {@code net.minecraft.core.Direction}. */
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

	/** True when any part of the voxel sits behind the camera plane. */
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

	/**
	 * Samples the block's texture where the ray landed, then lights it from a fixed world-space
	 * direction so a face keeps its brightness as the model spins.
	 */
	private static int shade(BlockTextures.Faces faces, int face, double hitX, double hitY, double hitZ) {
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

		return light(faces.sample(face, u, v), face);
	}

	/** Vanilla's directional face shading, so shape stays readable without a light source. */
	private static int light(int color, int face) {
		double brightness = switch (face) {
			case Face.UP -> 1.0D;
			case Face.DOWN -> 0.5D;
			case Face.NORTH, Face.SOUTH -> 0.8D;
			case Face.WEST, Face.EAST -> 0.65D;
			// Geometry with no cube face of its own (crops, torches) reads best unshaded.
			default -> 0.9D;
		};

		int red = (int) Math.min(255.0D, ((color >> 16) & 0xFF) * brightness);
		int green = (int) Math.min(255.0D, ((color >> 8) & 0xFF) * brightness);
		int blue = (int) Math.min(255.0D, (color & 0xFF) * brightness);
		return 0xFF000000 | (red << 16) | (green << 8) | blue;
	}

	private static double frac(double value) {
		double fraction = value - Math.floor(value);
		return fraction < 0.0D ? fraction + 1.0D : fraction;
	}

	// ------------------------------------------------------------------ texture plumbing

	/** Must run on the render thread. */
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

				if (previous != null) {
					previous.close();
				}
			}

			shown = frame.view();
		}

		startNext();
	}

	/** @return the current preview texture, or null while the first frame is still being built */
	public static @Nullable Identifier texture(int slot) {
		if (shown == null) {
			return null;
		}

		return shown.slot() == normalise(slot) ? viewId : null;
	}

	public static void releaseAll() {
		if (viewId != null) {
			Minecraft.getInstance().getTextureManager().release(viewId);
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
