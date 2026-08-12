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
 * for the hovered and disabled states, which is why the sheet carries three copies side by side.
 */
public final class IndexIcon implements IGuiIcon {
	public static final IndexIcon INSTANCE = new IndexIcon();

	private static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(SchematicIndexMod.MOD_ID, "textures/gui/icons.png");

	private IndexIcon() {
	}

	@Override
	public int getWidth() {
		return 16;
	}

	@Override
	public int getHeight() {
		return 16;
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
		fi.dy.masa.malilib.render.RenderUtils.drawTexturedRect(ctx, TEXTURE, x, y, 0, 0, 16, 16);
	}
}
