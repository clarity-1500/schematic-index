package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.resources.Identifier;

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
