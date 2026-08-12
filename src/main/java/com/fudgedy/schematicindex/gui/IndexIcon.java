package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.resources.Identifier;

/**
 * The mod's icon inside Litematica's menu: the chiseled bookshelf face.
 *
 * <p>MaLiLib blits icons with a hardcoded 1/256 pixel scale, so the texture has to be a 256x256
 * sheet - handing it a bare 16x16 file samples a single pixel. It also offsets u by the icon width
 * for the hovered and disabled states, which is why the sheet carries three 12px copies side by
 * side.
 *
 * <p>The art is 12px, so the icon reports 12: a 16px width made the button draw the icon
 * edge-to-edge and, because MaLiLib strides the state copies by the reported width, sampled 4px of
 * the next copy on every state. Reporting the true 12 both insets the icon within the 20px button
 * and lands each hover/disabled state squarely on its own copy.
 */
public final class IndexIcon implements IGuiIcon {
	public static final IndexIcon INSTANCE = new IndexIcon();

	private static final int SIZE = 12;

	private static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(SchematicIndexMod.MOD_ID, "textures/gui/icons.png");

	private IndexIcon() {
	}

	@Override
	public int getWidth() {
		return SIZE;
	}

	@Override
	public int getHeight() {
		return SIZE;
	}

	@Override
	public int getU() {
		return 0;
	}

	@Override
	public int getV() {
		return 0;
	}

	@Override
	public Identifier getTexture() {
		return TEXTURE;
	}

	@Override
	public void renderAt(GuiContext ctx, int x, int y, float zLevel, boolean enabled, boolean selected) {
		fi.dy.masa.malilib.render.RenderUtils.drawTexturedRect(ctx, TEXTURE, x, y, 0, 0, SIZE, SIZE);
	}
}
