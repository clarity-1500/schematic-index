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
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
	 * @param cube render as a solid block even though there are no quads - fluids have no block
	 *             model and no outline shape, so without this they vanish entirely
	 */
	public record Raw(List<RawQuad> quads, boolean cube) {
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

		Shape(boolean fullCube, @Nullable Quad[] quads, BlockTextures.Faces faces) {
			this.fullCube = fullCube;
			this.quads = quads;
			this.faces = faces;
		}

		public boolean fullCube() {
			return this.fullCube;
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
				// discards them as invisible.
				if (!state.getFluidState().isEmpty()) {
					return new Raw(quads, true);
				}

				// Signs, chests, beds and banners have empty models too, but they do have an outline.
				// Falling back to a full cube made a sign look like a block of wood, so build boxes
				// from that outline instead and skin them with the right texture.
				fromOutline(quads, state, model.particleIcon().contents().name());
			}
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("No model geometry for {}", state, e);
		}

		return new Raw(quads, false);
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

	private static final float[] CHEST_TOP = {28.0F / 64.0F, 0.0F, 42.0F / 64.0F, 14.0F / 64.0F};
	private static final float[] CHEST_SIDE = {14.0F / 64.0F, 33.0F / 64.0F, 28.0F / 64.0F, 43.0F / 64.0F};

	private static @Nullable Skin skinFor(BlockState state) {
		String chest = null;

		if (state.is(Blocks.ENDER_CHEST)) {
			chest = "ender";
		} else if (state.is(Blocks.TRAPPED_CHEST)) {
			chest = "trapped";
		} else if (state.is(Blocks.CHEST)) {
			chest = "normal";
		}

		if (chest == null) {
			return null;
		}

		try {
			return new Skin(Sheets.CHEST_MAPPER.defaultNamespaceApply(chest).texture(), CHEST_TOP, CHEST_SIDE);
		} catch (Throwable e) {
			return null;
		}
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

		out.add(new RawQuad(xs, ys, zs, us, vs, texture, direction.ordinal(), BlockTextures.NO_TINT));
	}

	private static float normalise(float value, float min, float max) {
		float span = max - min;
		return span == 0.0F ? 0.0F : (value - min) / span;
	}

	// ------------------------------------------------------------------ assembly

	/** Worker thread: resolves the textures the quads reference. */
	public static Shape build(Raw raw, BlockTextures.Faces faces) {
		if (raw.quads().isEmpty()) {
			return new Shape(raw.cube(), null, faces);
		}

		if (isUnitCube(raw.quads())) {
			return new Shape(true, null, faces);
		}

		Quad[] quads = new Quad[raw.quads().size()];

		for (int i = 0; i < quads.length; i++) {
			RawQuad source = raw.quads().get(i);
			quads[i] = new Quad(source, BlockTextures.texture(source.texture()));
		}

		return new Shape(false, quads, faces);
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
