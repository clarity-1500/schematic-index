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

/**
 * Adds the library entry point to Litematica's own main menu.
 *
 * <p>Litematica builds its menu from a hardcoded enum, so there is no addon API - a mixin is the
 * only way in. Extending {@link GuiBase} gives us malilib's {@code addButton}, which is how
 * Syncmatica does the same thing. {@code remap = false} is required: Litematica's classes are not
 * part of the Minecraft mapping set.
 *
 * <p>The button sits top-right so it never collides with Litematica's left-hand column.
 */
@Mixin(GuiMainMenu.class)
public abstract class LitematicaMainMenuMixin extends GuiBase {
	@Unique
	private static final String SCHEMATICINDEX$LABEL = "The Schematic Index";

	@Inject(method = "initGui", at = @At("RETURN"), remap = false)
	private void schematicindex$addLibraryButton(CallbackInfo info) {
		// Directly under Configuration, which heads Litematica's second column at y=30. It leaves an
		// 88px gap before Schematic Manager, so this slots in without displacing anything.
		int buttonWidth = schematicindex$columnWidth();
		int x = 12 + buttonWidth + 20;

		// Icon left, text left - matching every other button in the menu.
		ButtonGeneric button = new ButtonGeneric(x, 52, buttonWidth, 20, SCHEMATICINDEX$LABEL, IndexIcon.INSTANCE);
		button.setTextCentered(false);
		button.setIconAlignment(LeftRight.LEFT);
		this.addButton(button, (pressed, mouseButton) -> {
			Minecraft client = Minecraft.getInstance();
			client.setScreen(new IndexScreen(this));
		});
	}

	/**
	 * Mirrors Litematica's own private getButtonWidth() so the button lines up with its column
	 * exactly rather than guessing at a width.
	 */
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
