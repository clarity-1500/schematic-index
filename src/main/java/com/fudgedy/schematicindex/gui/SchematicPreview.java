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
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

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
 * Renders a schematic to an isometric voxel image.
 *
 * <p>Not a textured 3D scene - each block is drawn as a shaded cube in its map colour, the same
 * palette maps use. That gives a readable preview of a build's shape and materials without a custom
 * GPU pipeline, and it rasterises into a plain int buffer so the whole thing happens off-thread.
 *
 * <p>Parsing is Litematica's own {@link LitematicaSchematic}, so every format it reads works here.
 *
 * <p><b>Temporary:</b> files come from the local Litematica schematics folder. The shipped mod will
 * render whatever it downloaded from the catalogue instead.
 */
public final class SchematicPreview {
	public static final int WIDTH = 480;
	public static final int HEIGHT = 270;

	private static final int MAX_VOXELS = 6_000_000;
	private static final int UPLOADS_PER_FRAME = 1;

	private static final List<Path> FILES = new ArrayList<>();
	private static final Map<String, Identifier> READY = new HashMap<>();
	private static final Set<String> REQUESTED = new HashSet<>();
	private static final Queue<Rendered> PENDING = new ConcurrentLinkedQueue<>();

	private static boolean scanned;

	private record Rendered(String key, NativeImage image) {
	}

	private SchematicPreview() {
	}

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

	public static String name(int slot) {
		if (FILES.isEmpty()) {
			return "";
		}

		String file = FILES.get(Math.floorMod(slot, FILES.size())).getFileName().toString();
		return file.endsWith(".litematic") ? file.substring(0, file.length() - 10) : file;
	}

	/** @return the rendered texture, or null while it is still being built */
	public static @Nullable Identifier texture(int slot, int rotation) {
		if (FILES.isEmpty()) {
			return null;
		}

		int index = Math.floorMod(slot, FILES.size());
		int turn = Math.floorMod(rotation, 4);
		String key = index + "-" + turn;
		Identifier ready = READY.get(key);

		if (ready != null) {
			return ready;
		}

		if (REQUESTED.add(key)) {
			CompletableFuture.runAsync(() -> render(key, FILES.get(index), turn));
		}

		return null;
	}

	// ------------------------------------------------------------------ rendering

	private static void render(String key, Path file, int rotation) {
		try {
			// NOT the public LitematicaSchematic(Path, CompoundTag, FileType) constructor: it calls
			// readFromNBT() before assigning its converter field, so any schematic old enough to need
			// conversion dies on a NullPointerException. createFromFile() takes the working path.
			LitematicaSchematic schematic = LitematicaSchematic.createFromFile(
					file.getParent(), file.getFileName().toString(), FileType.LITEMATICA_SCHEMATIC);

			if (schematic == null) {
				SchematicIndexMod.LOGGER.warn("Could not read {}", file.getFileName());
				return;
			}

			Voxels voxels = collect(schematic, rotation);

			if (voxels == null) {
				SchematicIndexMod.LOGGER.warn("Nothing to render in {}", file.getFileName());
				return;
			}

			PENDING.add(new Rendered(key, rasterise(voxels)));
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.warn("Could not render {}", file.getFileName(), e);
		}
	}

	/** Flattened colour grid, 0 meaning empty. */
	private record Voxels(int[] colors, int sizeX, int sizeY, int sizeZ) {
		int at(int x, int y, int z) {
			if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
				return 0;
			}

			return this.colors[(y * this.sizeZ + z) * this.sizeX + x];
		}
	}

	private static @Nullable Voxels collect(LitematicaSchematic schematic, int rotation) {
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

			Vec3i size = region.getValue();
			// Region sizes are signed: a negative axis means the region grows the other way.
			int endX = origin.getX() + size.getX() - (size.getX() < 0 ? -1 : 1);
			int endY = origin.getY() + size.getY() - (size.getY() < 0 ? -1 : 1);
			int endZ = origin.getZ() + size.getZ() - (size.getZ() < 0 ? -1 : 1);

			minX = Math.min(minX, Math.min(origin.getX(), endX));
			minY = Math.min(minY, Math.min(origin.getY(), endY));
			minZ = Math.min(minZ, Math.min(origin.getZ(), endZ));
			maxX = Math.max(maxX, Math.max(origin.getX(), endX));
			maxY = Math.max(maxY, Math.max(origin.getY(), endY));
			maxZ = Math.max(maxZ, Math.max(origin.getZ(), endZ));
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
		int[] colors = new int[sizeX * sizeY * sizeZ];

		for (Map.Entry<String, BlockPos> region : sizes.entrySet()) {
			String name = region.getKey();
			BlockPos origin = schematic.getSubRegionPosition(name);
			LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);

			if (origin == null || container == null) {
				continue;
			}

			Vec3i size = region.getValue();
			Vec3i containerSize = container.getSize();

			for (int cx = 0; cx < containerSize.getX(); cx++) {
				for (int cy = 0; cy < containerSize.getY(); cy++) {
					for (int cz = 0; cz < containerSize.getZ(); cz++) {
						BlockState state = container.get(cx, cy, cz);

						if (state == null || state.isAir()) {
							continue;
						}

						int worldX = origin.getX() + (size.getX() < 0 ? -cx : cx);
						int worldY = origin.getY() + (size.getY() < 0 ? -cy : cy);
						int worldZ = origin.getZ() + (size.getZ() < 0 ? -cz : cz);

						int gx = (worldX - minX) / step;
						int gy = (worldY - minY) / step;
						int gz = (worldZ - minZ) / step;

						if (gx >= sizeX || gy >= sizeY || gz >= sizeZ) {
							continue;
						}

						colors[(gy * sizeZ + gz) * sizeX + gx] = colorOf(state);
					}
				}
			}
		}

		Voxels voxels = new Voxels(colors, sizeX, sizeY, sizeZ);
		return rotation == 0 ? voxels : rotate(voxels, rotation);
	}

	private static int colorOf(BlockState state) {
		MapColor color = state.getBlock().defaultMapColor();
		int rgb = color == null ? 0x8A8A8A : color.col;
		return rgb == 0 ? 0xFF7F7F7F : 0xFF000000 | rgb;
	}

	/** Quarter turns about the vertical axis, so the preview can be spun without re-parsing. */
	private static Voxels rotate(Voxels source, int rotation) {
		boolean swap = rotation % 2 == 1;
		int sizeX = swap ? source.sizeZ() : source.sizeX();
		int sizeZ = swap ? source.sizeX() : source.sizeZ();
		int[] colors = new int[sizeX * source.sizeY() * sizeZ];

		for (int y = 0; y < source.sizeY(); y++) {
			for (int z = 0; z < source.sizeZ(); z++) {
				for (int x = 0; x < source.sizeX(); x++) {
					int value = source.at(x, y, z);

					if (value == 0) {
						continue;
					}

					int nx;
					int nz;

					switch (rotation) {
						case 1 -> {
							nx = source.sizeZ() - 1 - z;
							nz = x;
						}
						case 2 -> {
							nx = source.sizeX() - 1 - x;
							nz = source.sizeZ() - 1 - z;
						}
						default -> {
							nx = z;
							nz = source.sizeX() - 1 - x;
						}
					}

					colors[(y * sizeZ + nz) * sizeX + nx] = value;
				}
			}
		}

		return new Voxels(colors, sizeX, source.sizeY(), sizeZ);
	}

	private static NativeImage rasterise(Voxels voxels) {
		int[] pixels = new int[WIDTH * HEIGHT];

		// Cube half-width in pixels, chosen so the whole build fits the frame.
		int spread = voxels.sizeX() + voxels.sizeZ();
		int cube = Math.max(1, Math.min(
				(WIDTH - 8) / Math.max(1, spread),
				(HEIGHT - 8) * 2 / Math.max(1, spread + voxels.sizeY() * 2)));
		cube = Math.min(cube, 10);

		Stencil stencil = new Stencil(cube);

		int drawWidth = spread * cube;
		int drawHeight = spread * cube / 2 + voxels.sizeY() * cube + cube;
		int originX = (WIDTH - drawWidth) / 2 + voxels.sizeZ() * cube;
		int originY = (HEIGHT - drawHeight) / 2 + voxels.sizeY() * cube;

		// Painter's order: farthest depth first, then bottom to top.
		for (int depth = 0; depth <= voxels.sizeX() + voxels.sizeZ() - 2; depth++) {
			int from = Math.max(0, depth - voxels.sizeZ() + 1);
			int to = Math.min(depth, voxels.sizeX() - 1);

			for (int x = from; x <= to; x++) {
				int z = depth - x;

				for (int y = 0; y < voxels.sizeY(); y++) {
					int color = voxels.at(x, y, z);

					if (color == 0) {
						continue;
					}

					// The +x, +y and +z neighbours are the three faces pointing at the camera.
					if (voxels.at(x + 1, y, z) != 0 && voxels.at(x, y + 1, z) != 0 && voxels.at(x, y, z + 1) != 0) {
						continue;
					}

					int px = originX + (x - z) * cube;
					int py = originY + (x + z) * cube / 2 - y * cube;
					stencil.stamp(pixels, px, py, color);
				}
			}
		}

		NativeImage image = new NativeImage(NativeImage.Format.RGBA, WIDTH, HEIGHT, false);

		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int argb = pixels[y * WIDTH + x];
				// NativeImage is ABGR in memory, so swap the red and blue channels.
				int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
				image.setPixel(x, y, abgr);
			}
		}

		return image;
	}

	/**
	 * A single cube stamped as three shaded faces. Building the face masks once and reusing them for
	 * every block keeps the polygon maths out of the inner loop.
	 */
	private static final class Stencil {
		private final int size;
		private final byte[] faces;

		private Stencil(int cube) {
			this.size = cube * 2;
			this.faces = new byte[this.size * this.size];

			for (int y = 0; y < this.size; y++) {
				for (int x = 0; x < this.size; x++) {
					this.faces[y * this.size + x] = face(x + 0.5D, y + 0.5D, cube);
				}
			}
		}

		private static byte face(double px, double py, int cube) {
			// Top: diamond with corners (cube,0) (2*cube,cube/2) (cube,cube) (0,cube/2)
			if (Math.abs(px - cube) / cube + Math.abs(py - cube / 2.0D) / (cube / 2.0D) <= 1.0D) {
				return 1;
			}

			if (px < cube) {
				double edge = cube / 2.0D + px / 2.0D;
				return py >= edge && py < edge + cube ? (byte) 2 : 0;
			}

			double edge = cube - (px - cube) / 2.0D;
			return py >= edge && py < edge + cube ? (byte) 3 : 0;
		}

		private void stamp(int[] pixels, int originX, int originY, int color) {
			for (int y = 0; y < this.size; y++) {
				int targetY = originY + y;

				if (targetY < 0 || targetY >= HEIGHT) {
					continue;
				}

				for (int x = 0; x < this.size; x++) {
					byte face = this.faces[y * this.size + x];

					if (face == 0) {
						continue;
					}

					int targetX = originX + x;

					if (targetX < 0 || targetX >= WIDTH) {
						continue;
					}

					pixels[targetY * WIDTH + targetX] = shade(color, face);
				}
			}
		}

		private static int shade(int color, byte face) {
			double factor = switch (face) {
				case 1 -> 1.0D;
				case 2 -> 0.72D;
				default -> 0.52D;
			};

			int r = (int) (((color >> 16) & 0xFF) * factor);
			int g = (int) (((color >> 8) & 0xFF) * factor);
			int b = (int) ((color & 0xFF) * factor);
			return 0xFF000000 | (r << 16) | (g << 8) | b;
		}
	}

	// ------------------------------------------------------------------ texture plumbing

	/** Must run on the render thread. */
	public static void uploadPending() {
		Minecraft client = Minecraft.getInstance();

		for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
			Rendered rendered = PENDING.poll();

			if (rendered == null) {
				return;
			}

			Identifier id = Identifier.fromNamespaceAndPath(SchematicIndexMod.MOD_ID, "preview/" + rendered.key());
			client.getTextureManager().register(id, new DynamicTexture(() -> "schematicindex-preview", rendered.image()));
			READY.put(rendered.key(), id);
		}
	}

	public static void releaseAll() {
		Minecraft client = Minecraft.getInstance();

		for (Identifier id : READY.values()) {
			client.getTextureManager().release(id);
		}

		READY.clear();
		REQUESTED.clear();

		Rendered pending;

		while ((pending = PENDING.poll()) != null) {
			pending.image().close();
		}
	}
}
