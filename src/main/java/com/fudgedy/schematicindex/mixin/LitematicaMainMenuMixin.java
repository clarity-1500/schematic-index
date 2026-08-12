package com.fudgedy.schematicindex.mixin;

import com.fudgedy.schematicindex.gui.IndexIcon;
import com.fudgedy.schematicindex.gui.IndexScreen;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiMainMenu.class)
public abstract class LitematicaMainMenuMixin extends GuiBase {
	@Unique
	private static final String SCHEMATICINDEX$LABEL = "The Schematic Index";

	@Inject(method = "initGui", at = @At("RETURN"), remap = false)
	private void schematicindex$addLibraryButton(CallbackInfo info) {
		int buttonWidth = schematicindex$columnWidth();
		int x = 12 + buttonWidth + 20;

		ButtonGeneric button = new ButtonGeneric(x, 52, buttonWidth, 20, SCHEMATICINDEX$LABEL, IndexIcon.INSTANCE);
		button.setTextCentered(false);
		button.setIconAlignment(LeftRight.LEFT);
		this.addButton(button, (pressed, mouseButton) -> {
			Minecraft client = Minecraft.getInstance();
			client.setScreen(new IndexScreen(this));
		});
	}

	@Unique
	private int schematicindex$columnWidth() {
		int width = 0;

		for (GuiMainMenu.ButtonListenerChangeMenu.ButtonType type
				: GuiMainMenu.ButtonListenerChangeMenu.ButtonType.values()) {
			width = Math.max(width, this.getStringWidth(type.getDisplayName()) + 30);
		}

		for (SelectionMode mode : SelectionMode.values()) {
			String label = StringUtils.translate("litematica.gui.button.area_selection_mode", mode.getDisplayName());
			width = Math.max(width, this.getStringWidth(label) + 10);
		}

		return width;
	}
}
