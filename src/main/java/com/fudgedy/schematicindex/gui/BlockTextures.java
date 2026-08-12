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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns block states into the pixels the preview samples.
 *
 * <p>Two stages, because they have different threading rules. Resolving which sprite a face uses
 * needs the block model registry and therefore the render thread; reading the sprite's PNG back off
 * the resource manager does not, and there can be dozens of them, so that half runs on a worker.
 *
 * <p>Sprite pixels are read from the source PNG rather than the stitched atlas: the atlas image is
 * private to {@code SpriteContents}, and going through the resource manager picks up the player's
 * resource pack for free.
 */
public final class BlockTextures {
	public static final int NO_TINT = 0xFFFFFFFF;

	/** What the render thread hands back: which sprite each face uses, and its tint. */
	public record Resolved(Identifier[] sprites, int[] tints) {
	}

	/** One block's six faces. Missing faces fall back to {@link #averageColor}. */
	public static final class Faces {
		private final Texture[] byFace = new Texture[6];
		private final int[] tints = new int[6];
		private int averageColor = 0xFF8A8A8A;

		Faces() {
			for (int i = 0; i < this.tints.length; i++) {
				this.tints[i] = NO_TINT;
			}
		}

		public int sample(int face, double u, double v) {
			Texture texture = this.byFace[face];

			if (texture == null) {
				return this.averageColor;
			}

			int color = texture.sample(u, v);
			// Cutout textures (glass, leaves, rails) leave holes; show the block's tone instead of
			// punching a transparent gap through the middle of a solid-looking build.
			return (color >>> 24) < 16 ? this.averageColor : tint(color, this.tints[face]);
		}
	}

	record Texture(int[] pixels, int width, int height) {
		int sample(double u, double v) {
			int x = Math.max(0, Math.min(this.width - 1, (int) (u * this.width)));
			int y = Math.max(0, Math.min(this.height - 1, (int) (v * this.height)));
			return this.pixels[y * this.width + x];
		}
	}

	private static final Map<Identifier, Texture> CACHE = new HashMap<>();

	private BlockTextures() {
	}

	/**
	 * Multiplies a texel by a tint colour. Leaves, grass, vines and water all ship greyscale
	 * textures that only become green or blue once the biome colour is applied - without this they
	 * render a flat grey.
	 */
	public static int tint(int color, int tint) {
		if (tint == NO_TINT) {
			return color;
		}

		int red = ((color >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
		int green = ((color >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
		int blue = (color & 0xFF) * (tint & 0xFF) / 255;
		return (color & 0xFF000000) | (red << 16) | (green << 8) | blue;
	}

	/**
	 * Water's blue does not come from the block colour system - the fluid renderer applies it from
	 * the biome - so it has to be supplied here or water renders as the grey texture it ships as.
	 */
	private static final int DEFAULT_WATER = 0xFF3F76E4;

	/** Render thread only - reads the baked model registry and the block colour providers. */
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

	/** Render thread only. A null level makes the colour providers fall back to their defaults. */
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

		// Blocks whose geometry is not cube-shaped (crops, torches) put everything in the
		// direction-less bucket.
		return quadFor(parts, null);
	}

	/** Worker thread - decodes the PNGs behind the resolved sprite names. */
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

		// A tinted block whose texture failed to load should still not be grey.
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

			// Animated textures stack their frames vertically - keep only the first.
			int frameHeight = height > width && height % width == 0 ? width : height;
			int[] pixels = new int[width * frameHeight];

			for (int y = 0; y < frameHeight; y++) {
				for (int x = 0; x < width; x++) {
					pixels[y * width + x] = image.getPixel(x, y);
				}
			}

			image.close();
			return new Texture(pixels, width, frameHeight);
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
