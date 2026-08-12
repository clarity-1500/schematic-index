package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BlockTextures {
	public static final int NO_TINT = 0xFFFFFFFF;

	public record Resolved(Identifier[] sprites, int[] tints) {
	}

	public static final class Faces {
		private final Texture[] byFace = new Texture[6];
		private final int[] tints = new int[6];
		private int averageColor = 0xFF8A8A8A;

		Faces() {
			for (int i = 0; i < this.tints.length; i++) {
				this.tints[i] = NO_TINT;
			}
		}

		public int sample(int face, double u, double v, int lod) {
			Texture texture = this.byFace[face];

			if (texture == null) {
				return this.averageColor;
			}

			int color = texture.sample(u, v, lod);

			return (color >>> 24) < 16 ? this.averageColor : tint(color, this.tints[face]);
		}
	}

	record Texture(int[][] mips, int width, int height) {
		int sample(double u, double v, int lod) {
			int level = Math.max(0, Math.min(this.mips.length - 1, lod));
			int w = Math.max(1, this.width >> level);
			int h = Math.max(1, this.height >> level);
			int x = Math.max(0, Math.min(w - 1, (int) (u * w)));
			int y = Math.max(0, Math.min(h - 1, (int) (v * h)));
			return this.mips[level][y * w + x];
		}
	}

	private static int[][] buildMips(int[] base, int width, int height) {
		List<int[]> levels = new ArrayList<>();
		levels.add(base);

		int cw = width;
		int ch = height;
		int[] current = base;

		while (cw > 1 || ch > 1) {
			int nw = Math.max(1, cw >> 1);
			int nh = Math.max(1, ch >> 1);
			int[] next = new int[nw * nh];

			for (int y = 0; y < nh; y++) {
				for (int x = 0; x < nw; x++) {
					int x0 = Math.min(cw - 1, x * 2);
					int x1 = Math.min(cw - 1, x * 2 + 1);
					int y0 = Math.min(ch - 1, y * 2);
					int y1 = Math.min(ch - 1, y * 2 + 1);
					next[y * nw + x] = average(current[y0 * cw + x0], current[y0 * cw + x1],
							current[y1 * cw + x0], current[y1 * cw + x1]);
				}
			}

			levels.add(next);
			current = next;
			cw = nw;
			ch = nh;
		}

		return levels.toArray(new int[0][]);
	}

	private static int average(int a, int b, int c, int d) {
		int c24 = ((a >>> 24) + (b >>> 24) + (c >>> 24) + (d >>> 24)) / 4;
		int c16 = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF) + ((c >> 16) & 0xFF) + ((d >> 16) & 0xFF)) / 4;
		int c8 = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF) + ((c >> 8) & 0xFF) + ((d >> 8) & 0xFF)) / 4;
		int c0 = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF)) / 4;
		return (c24 << 24) | (c16 << 16) | (c8 << 8) | c0;
	}

	private static final Map<Identifier, Texture> CACHE = new HashMap<>();

	private BlockTextures() {
	}

	public static int tint(int color, int tint) {
		if (tint == NO_TINT) {
			return color;
		}

		int red = ((color >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
		int green = ((color >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
		int blue = (color & 0xFF) * (tint & 0xFF) / 255;
		return (color & 0xFF000000) | (red << 16) | (green << 8) | blue;
	}

	public static final int WATER_TINT = 0xFF3F76E4;
	private static final int DEFAULT_WATER = WATER_TINT;

	public static Resolved resolveSprites(BlockState state) {
		Identifier[] names = new Identifier[6];
		int[] tints = new int[6];

		for (int i = 0; i < tints.length; i++) {
			tints[i] = NO_TINT;
		}

		if (!state.getFluidState().isEmpty()) {
			boolean lava = state.getFluidState().is(FluidTags.LAVA);
			Identifier sprite = Identifier.withDefaultNamespace(lava ? "block/lava_still" : "block/water_still");

			for (int i = 0; i < names.length; i++) {
				names[i] = sprite;
				tints[i] = lava ? NO_TINT : DEFAULT_WATER;
			}

			return new Resolved(names, tints);
		}

		try {
			BlockStateModel model = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getBlockModel(state);
			List<BlockModelPart> parts = model.collectParts(RandomSource.create(42L));

			for (Direction direction : Direction.values()) {
				BakedQuad quad = quadFor(parts, direction);

				if (quad != null) {
					names[direction.ordinal()] = quad.sprite().contents().name();
					tints[direction.ordinal()] = tintFor(state, quad.tintIndex());
				}
			}

			Identifier particle = model.particleIcon().contents().name();

			for (int i = 0; i < names.length; i++) {
				if (names[i] == null) {
					names[i] = particle;
				}
			}
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("No model textures for {}", state, e);
		}

		return new Resolved(names, tints);
	}

	public static int tintFor(BlockState state, int tintIndex) {
		if (tintIndex < 0) {
			return NO_TINT;
		}

		try {
			return 0xFF000000 | Minecraft.getInstance().getBlockColors().getColor(state, null, null, tintIndex);
		} catch (Throwable e) {
			return NO_TINT;
		}
	}

	private static @Nullable BakedQuad quadFor(List<BlockModelPart> parts, @Nullable Direction direction) {
		for (BlockModelPart part : parts) {
			List<BakedQuad> quads = part.getQuads(direction);

			if (!quads.isEmpty()) {
				return quads.getFirst();
			}
		}

		if (direction == null) {
			return null;
		}

		return quadFor(parts, null);
	}

	public static Faces load(Resolved resolved, BlockState state) {
		Faces faces = new Faces();
		MapColor mapColor = state.getBlock().defaultMapColor();

		if (mapColor != null && mapColor.col != 0) {
			faces.averageColor = 0xFF000000 | mapColor.col;
		}

		for (int i = 0; i < resolved.sprites().length; i++) {
			if (resolved.sprites()[i] != null) {
				faces.byFace[i] = texture(resolved.sprites()[i]);
			}

			faces.tints[i] = resolved.tints()[i];
		}

		faces.averageColor = tint(faces.averageColor, resolved.tints()[Direction.UP.ordinal()]);
		return faces;
	}

	static synchronized @Nullable Texture texture(Identifier sprite) {
		Texture cached = CACHE.get(sprite);

		if (cached != null || CACHE.containsKey(sprite)) {
			return cached;
		}

		Texture loaded = read(sprite);
		CACHE.put(sprite, loaded);
		return loaded;
	}

	private static @Nullable Texture read(Identifier sprite) {
		Identifier file = Identifier.fromNamespaceAndPath(
				sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);

		if (resource.isEmpty()) {
			return null;
		}

		try (InputStream input = resource.get().open()) {
			NativeImage image = NativeImage.read(input);
			int width = image.getWidth();
			int height = image.getHeight();

			int frameHeight = height > width && height % width == 0 ? width : height;
			int[] pixels = new int[width * frameHeight];

			for (int y = 0; y < frameHeight; y++) {
				for (int x = 0; x < width; x++) {
					pixels[y * width + x] = image.getPixel(x, y);
				}
			}

			image.close();
			return new Texture(buildMips(pixels, width, frameHeight), width, frameHeight);
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("Could not read texture {}", file, e);
			return null;
		}
	}

	public static void clear() {
		synchronized (BlockTextures.class) {
			CACHE.clear();
		}
	}
}
