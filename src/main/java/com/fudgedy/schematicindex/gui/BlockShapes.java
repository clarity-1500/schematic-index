package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Real block geometry for the preview: the baked model's quads rather than a full cube.
 *
 * <p>Extraction has to happen on the render thread because it reads the baked model registry, so a
 * {@link Raw} snapshot is taken there and turned into a {@link Shape} - textures loaded, UVs
 * normalised - on a worker afterwards.
 *
 * <p>Blocks whose geometry is exactly the unit cube (most of any build) are flagged, and the
 * raycaster keeps using its cheap face-aligned path for those. Only stairs, slabs, torches and the
 * like pay for triangle intersection.
 */
public final class BlockShapes {
	/**
	 * Model geometry straight off the render thread, before textures are resolved.
	 *
	 * @param cube        render as a solid block even though there are no quads - fluids have no block
	 *                    model and no outline shape, so without this they vanish entirely
	 * @param water       a water fluid: the raycaster composites it as a translucent volume rather
	 *                    than shading it as an opaque cube. Lava keeps {@code cube} but not this.
	 * @param waterlogged real geometry that also sits in water (submerged stairs, slabs, fences); the
	 *                    raycaster lays a water layer over whatever the ray hits inside the voxel
	 */
	public record Raw(List<RawQuad> quads, boolean cube, boolean water, boolean waterlogged) {
	}

	public record RawQuad(float[] xs, float[] ys, float[] zs, float[] us, float[] vs,
			Identifier texture, int face, int tint) {
	}

	/** One triangulated, textured quad in block-local space (0..1). */
	public static final class Quad {
		final float[] xs;
		final float[] ys;
		final float[] zs;
		final float[] us;
		final float[] vs;
		final BlockTextures.Texture texture;
		final int face;
		final int tint;

		Quad(RawQuad raw, @Nullable BlockTextures.Texture texture) {
			this.xs = raw.xs();
			this.ys = raw.ys();
			this.zs = raw.zs();
			this.us = raw.us();
			this.vs = raw.vs();
			this.texture = texture;
			this.face = raw.face();
			this.tint = raw.tint();
		}
	}

	public static final class Shape {
		final boolean fullCube;
		final Quad[] quads;
		final BlockTextures.Faces faces;
		final boolean waterVolume;
		final boolean waterlogged;

		Shape(boolean fullCube, @Nullable Quad[] quads, BlockTextures.Faces faces,
				boolean waterVolume, boolean waterlogged) {
			this.fullCube = fullCube;
			this.quads = quads;
			this.faces = faces;
			this.waterVolume = waterVolume;
			this.waterlogged = waterlogged;
		}

		public boolean fullCube() {
			return this.fullCube;
		}

		/** A water fluid: composited as a translucent volume instead of an opaque cube. */
		public boolean waterVolume() {
			return this.waterVolume;
		}

		/** Real geometry submerged in water: the ray tints whatever it hits inside the voxel blue. */
		public boolean waterlogged() {
			return this.waterlogged;
		}

		/** Nothing to draw at all - the model was empty and the block has no outline either. */
		public boolean invisible() {
			return !this.fullCube && this.quads == null;
		}

		public BlockTextures.Faces faces() {
			return this.faces;
		}
	}

	private BlockShapes() {
	}

	// ------------------------------------------------------------------ extraction

	/** Render thread only. */
	public static Raw extract(BlockState state) {
		List<RawQuad> quads = new ArrayList<>();

		try {
			BlockStateModel model = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getBlockModel(state);
			List<BlockModelPart> parts = model.collectParts(RandomSource.create(42L));

			for (BlockModelPart part : parts) {
				for (Direction direction : Direction.values()) {
					collect(quads, part.getQuads(direction), state);
				}

				// Geometry that is not tied to a cube face (crops, torches, rails) lives here.
				collect(quads, part.getQuads(null), state);
			}

			if (quads.isEmpty()) {
				// Fluids have neither a block model nor an outline shape - the fluid renderer draws
				// them in game - so they need to be claimed as solid blocks before the outline path
				// discards them as invisible. Water is composited translucently downstream; lava is
				// just an opaque cube, so only water carries the flag.
				if (!state.getFluidState().isEmpty()) {
					boolean water = state.getFluidState().is(FluidTags.WATER);
					return new Raw(quads, true, water, false);
				}

				// Signs and banners have no collision outline at all, so build a representative shape
				// for them by hand. Chests, shulkers, beds and the like do have an outline: build
				// boxes from it and skin them with the right entity texture.
				if (synthesize(quads, state)) {
					return new Raw(quads, false, false, isWaterlogged(state));
				}

				fromOutline(quads, state, model.particleIcon().contents().name());
			}
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("No model geometry for {}", state, e);
		}

		// Waterlogged stairs, slabs, fences and the like sit in water in game. Rather than washing the
		// block blue, the flag tells the raycaster to lay a translucent water layer over whatever the
		// ray hits inside the voxel, so the block keeps its real texture and reads as submerged.
		return new Raw(quads, false, false, isWaterlogged(state));
	}

	private static boolean isWaterlogged(BlockState state) {
		return state.hasProperty(BlockStateProperties.WATERLOGGED)
				&& state.getValue(BlockStateProperties.WATERLOGGED);
	}

	private static void collect(List<RawQuad> out, List<BakedQuad> quads, BlockState state) {
		for (BakedQuad quad : quads) {
			TextureAtlasSprite sprite = quad.sprite();
			float[] xs = new float[4];
			float[] ys = new float[4];
			float[] zs = new float[4];
			float[] us = new float[4];
			float[] vs = new float[4];

			for (int i = 0; i < 4; i++) {
				Vector3fc position = quad.position(i);
				xs[i] = position.x();
				ys[i] = position.y();
				zs[i] = position.z();

				long packed = quad.packedUV(i);
				// Atlas coordinates: rebase them onto the sprite so the preview can sample the
				// sprite's own PNG rather than a stitched atlas it never builds.
				us[i] = normalise(UVPair.unpackU(packed), sprite.getU0(), sprite.getU1());
				vs[i] = normalise(UVPair.unpackV(packed), sprite.getV0(), sprite.getV1());
			}

			Direction direction = quad.direction();
			out.add(new RawQuad(xs, ys, zs, us, vs, sprite.contents().name(),
					direction == null ? -1 : direction.ordinal(),
					BlockTextures.tintFor(state, quad.tintIndex())));
		}
	}

	/**
	 * Block entities draw themselves from an entity sheet rather than a block texture, so the
	 * particle icon is misleading - an ender chest's particle is obsidian, which is why it rendered
	 * as a block of obsidian. These map the block to its real sheet plus the region of it worth
	 * showing: the lid for horizontal faces, the front panel for the sides.
	 */
	private record Skin(Identifier texture, float[] top, float[] side) {
	}

	// Regions are [u0, v0, u1, v1] in 0..1 texture space. The chest and shulker sheets are 64x64; the
	// bed sheet is 64x64. Each picks out the part of the entity unwrap worth showing on a box: a
	// horizontal face and a vertical one.
	private static final float[] CHEST_TOP = {28.0F / 64.0F, 0.0F, 42.0F / 64.0F, 14.0F / 64.0F};
	private static final float[] CHEST_SIDE = {14.0F / 64.0F, 33.0F / 64.0F, 28.0F / 64.0F, 43.0F / 64.0F};
	// Shulker lid box (texOffs 0,0, size 16x12x16): the top face and the front panel with the "face".
	private static final float[] SHULKER_TOP = {16.0F / 64.0F, 0.0F, 32.0F / 64.0F, 16.0F / 64.0F};
	private static final float[] SHULKER_SIDE = {16.0F / 64.0F, 16.0F / 64.0F, 32.0F / 64.0F, 28.0F / 64.0F};
	// Bed head main box (texOffs 0,0, size 16x16x6): the blanket face, plus a wood side strip.
	private static final float[] BED_TOP = {6.0F / 64.0F, 6.0F / 64.0F, 22.0F / 64.0F, 22.0F / 64.0F};
	private static final float[] BED_FOOT_TOP = {6.0F / 64.0F, 28.0F / 64.0F, 22.0F / 64.0F, 44.0F / 64.0F};
	private static final float[] BED_SIDE = {0.0F, 6.0F / 64.0F, 6.0F / 64.0F, 22.0F / 64.0F};
	// Sign board front (64x32 sheet, board texOffs 0,0 size 24x12x2).
	private static final float[] SIGN_BOARD = {2.0F / 64.0F, 2.0F / 32.0F, 26.0F / 64.0F, 14.0F / 32.0F};
	// Banner cloth flag front (64x64 base sheet, cloth 20x40 at 1,1).
	private static final float[] BANNER_CLOTH = {1.0F / 64.0F, 1.0F / 64.0F, 21.0F / 64.0F, 41.0F / 64.0F};

	private static @Nullable Skin skinFor(BlockState state) {
		String chest = null;

		if (state.is(Blocks.ENDER_CHEST)) {
			chest = "ender";
		} else if (state.is(Blocks.TRAPPED_CHEST)) {
			chest = "trapped";
		} else if (state.is(Blocks.CHEST)) {
			chest = "normal";
		}

		if (chest != null) {
			try {
				return new Skin(Sheets.CHEST_MAPPER.defaultNamespaceApply(chest).texture(), CHEST_TOP, CHEST_SIDE);
			} catch (Throwable e) {
				return null;
			}
		}

		if (state.getBlock() instanceof ShulkerBoxBlock shulker) {
			try {
				DyeColor color = shulker.getColor();
				Identifier texture = color == null
						? Sheets.DEFAULT_SHULKER_TEXTURE_LOCATION.texture()
						: Sheets.getShulkerBoxMaterial(color).texture();
				return new Skin(texture, SHULKER_TOP, SHULKER_SIDE);
			} catch (Throwable e) {
				return null;
			}
		}

		if (state.getBlock() instanceof BedBlock bed) {
			try {
				Identifier texture = Sheets.getBedMaterial(bed.getColor()).texture();
				boolean foot = state.getValue(BedBlock.PART) == BedPart.FOOT;
				return new Skin(texture, foot ? BED_FOOT_TOP : BED_TOP, BED_SIDE);
			} catch (Throwable e) {
				return null;
			}
		}

		if (state.getBlock() instanceof DecoratedPotBlock) {
			try {
				// The pot-body texture on every face reads as a decorated pot (null region = whole sprite).
				return new Skin(Sheets.DECORATED_POT_SIDE.texture(), null, null);
			} catch (Throwable e) {
				return null;
			}
		}

		return null;
	}

	/**
	 * Signs and banners are drawn entirely by a block-entity renderer, so they have neither a block
	 * model nor a collision outline to build boxes from. This stands in a representative shape - a
	 * board on a post, or a cloth flag - skinned with the real entity texture.
	 *
	 * @return true when a shape was produced, so the caller skips the outline path
	 */
	private static boolean synthesize(List<RawQuad> out, BlockState state) {
		if (state.getBlock() instanceof SignBlock sign) {
			try {
				Identifier texture = Sheets.getSignMaterial(sign.type()).texture();

				if (state.getBlock() instanceof WallSignBlock) {
					// A wall sign is a board flat against the block, no post.
					emitBox(out, texture, SIGN_BOARD, BlockTextures.NO_TINT, 0.06F, 0.3F, 0.02F, 0.94F, 0.74F, 0.16F);
				} else {
					// A standing sign: a post in the lower half, the board across the top.
					emitBox(out, texture, SIGN_BOARD, BlockTextures.NO_TINT, 0.44F, 0.0F, 0.44F, 0.56F, 0.55F, 0.56F);
					emitBox(out, texture, SIGN_BOARD, BlockTextures.NO_TINT, 0.06F, 0.52F, 0.44F, 0.94F, 1.0F, 0.56F);
				}

				return true;
			} catch (Throwable e) {
				return false;
			}
		}

		if (state.getBlock() instanceof AbstractBannerBlock banner) {
			try {
				Identifier texture = Sheets.BANNER_BASE.texture();
				int cloth = banner.getColor().getTextureDiffuseColor();
				// A tall cloth flag, tinted to the banner's colour, on a short dark post.
				emitBox(out, texture, BANNER_CLOTH, 0xFF3A3A3A, 0.46F, 0.0F, 0.46F, 0.54F, 0.2F, 0.54F);
				emitBox(out, texture, BANNER_CLOTH, cloth, 0.12F, 0.16F, 0.47F, 0.88F, 0.98F, 0.53F);
				return true;
			} catch (Throwable e) {
				return false;
			}
		}

		return false;
	}

	/** Emits the six faces of an axis-aligned box, all skinned from one texture region. */
	private static void emitBox(List<RawQuad> out, Identifier texture, float @Nullable [] region, int tint,
			float x0, float y0, float z0, float x1, float y1, float z1) {
		addFace(out, texture, Direction.DOWN, region, tint,
				new float[]{x0, x1, x1, x0}, new float[]{y0, y0, y0, y0}, new float[]{z0, z0, z1, z1});
		addFace(out, texture, Direction.UP, region, tint,
				new float[]{x0, x1, x1, x0}, new float[]{y1, y1, y1, y1}, new float[]{z1, z1, z0, z0});
		addFace(out, texture, Direction.NORTH, region, tint,
				new float[]{x1, x0, x0, x1}, new float[]{y1, y1, y0, y0}, new float[]{z0, z0, z0, z0});
		addFace(out, texture, Direction.SOUTH, region, tint,
				new float[]{x0, x1, x1, x0}, new float[]{y1, y1, y0, y0}, new float[]{z1, z1, z1, z1});
		addFace(out, texture, Direction.WEST, region, tint,
				new float[]{x0, x0, x0, x0}, new float[]{y1, y1, y0, y0}, new float[]{z0, z1, z1, z0});
		addFace(out, texture, Direction.EAST, region, tint,
				new float[]{x1, x1, x1, x1}, new float[]{y1, y1, y0, y0}, new float[]{z1, z0, z0, z1});
	}

	/** Builds box geometry from a block's outline shape, for blocks with no model of their own. */
	private static void fromOutline(List<RawQuad> out, BlockState state, Identifier texture) {
		Skin skin = skinFor(state);
		Identifier surface = skin == null ? texture : skin.texture();
		VoxelShape shape;

		try {
			shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		} catch (Throwable e) {
			return;
		}

		if (shape == null || shape.isEmpty()) {
			return;
		}

		for (AABB box : shape.toAabbs()) {
			float x0 = (float) box.minX;
			float y0 = (float) box.minY;
			float z0 = (float) box.minZ;
			float x1 = (float) box.maxX;
			float y1 = (float) box.maxY;
			float z1 = (float) box.maxZ;

			float[] top = skin == null ? null : skin.top();
			float[] side = skin == null ? null : skin.side();

			addFace(out, surface, Direction.DOWN, top,
					new float[]{x0, x1, x1, x0}, new float[]{y0, y0, y0, y0}, new float[]{z0, z0, z1, z1});
			addFace(out, surface, Direction.UP, top,
					new float[]{x0, x1, x1, x0}, new float[]{y1, y1, y1, y1}, new float[]{z1, z1, z0, z0});
			addFace(out, surface, Direction.NORTH, side,
					new float[]{x1, x0, x0, x1}, new float[]{y1, y1, y0, y0}, new float[]{z0, z0, z0, z0});
			addFace(out, surface, Direction.SOUTH, side,
					new float[]{x0, x1, x1, x0}, new float[]{y1, y1, y0, y0}, new float[]{z1, z1, z1, z1});
			addFace(out, surface, Direction.WEST, side,
					new float[]{x0, x0, x0, x0}, new float[]{y1, y1, y0, y0}, new float[]{z0, z1, z1, z0});
			addFace(out, surface, Direction.EAST, side,
					new float[]{x1, x1, x1, x1}, new float[]{y1, y1, y0, y0}, new float[]{z1, z0, z0, z1});
		}
	}

	private static void addFace(List<RawQuad> out, Identifier texture, Direction direction,
			float @Nullable [] region, float[] xs, float[] ys, float[] zs) {
		addFace(out, texture, direction, region, BlockTextures.NO_TINT, xs, ys, zs);
	}

	private static void addFace(List<RawQuad> out, Identifier texture, Direction direction,
			float @Nullable [] region, int tint, float[] xs, float[] ys, float[] zs) {
		// Planar UVs: map the two axes the face spans straight onto the texture.
		float[] us = new float[4];
		float[] vs = new float[4];

		for (int i = 0; i < 4; i++) {
			switch (direction.getAxis()) {
				case X -> {
					us[i] = zs[i];
					vs[i] = 1.0F - ys[i];
				}
				case Y -> {
					us[i] = xs[i];
					vs[i] = zs[i];
				}
				default -> {
					us[i] = xs[i];
					vs[i] = 1.0F - ys[i];
				}
			}

			// Entity sheets hold many parts on one image, so squeeze the face into its own region.
			if (region != null) {
				us[i] = region[0] + us[i] * (region[2] - region[0]);
				vs[i] = region[1] + vs[i] * (region[3] - region[1]);
			}
		}

		out.add(new RawQuad(xs, ys, zs, us, vs, texture, direction.ordinal(), tint));
	}

	private static float normalise(float value, float min, float max) {
		float span = max - min;
		return span == 0.0F ? 0.0F : (value - min) / span;
	}

	// ------------------------------------------------------------------ assembly

	/** Worker thread: resolves the textures the quads reference. */
	public static Shape build(Raw raw, BlockTextures.Faces faces) {
		if (raw.quads().isEmpty()) {
			return new Shape(raw.cube(), null, faces, raw.water(), raw.waterlogged());
		}

		if (isUnitCube(raw.quads())) {
			return new Shape(true, null, faces, false, raw.waterlogged());
		}

		Quad[] quads = new Quad[raw.quads().size()];

		for (int i = 0; i < quads.length; i++) {
			RawQuad source = raw.quads().get(i);
			quads[i] = new Quad(source, BlockTextures.texture(source.texture()));
		}

		return new Shape(false, quads, faces, false, raw.waterlogged());
	}

	/**
	 * True when the geometry is exactly the six faces of the unit cube, which is the overwhelming
	 * majority of blocks in a build and lets the raycaster skip triangle work entirely.
	 */
	private static boolean isUnitCube(List<RawQuad> quads) {
		if (quads.size() != 6) {
			return false;
		}

		boolean[] seen = new boolean[6];

		for (RawQuad quad : quads) {
			if (quad.face() < 0) {
				return false;
			}

			for (int i = 0; i < 4; i++) {
				if (!isEdge(quad.xs()[i]) || !isEdge(quad.ys()[i]) || !isEdge(quad.zs()[i])) {
					return false;
				}
			}

			if (seen[quad.face()]) {
				return false;
			}

			seen[quad.face()] = true;
		}

		return true;
	}

	private static boolean isEdge(float value) {
		return value < 0.001F || value > 0.999F;
	}
}
