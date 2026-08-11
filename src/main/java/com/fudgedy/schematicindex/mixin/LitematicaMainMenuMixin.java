package com.fudgedy.schematicindex.mixin;

import com.fudgedy.schematicindex.gui.IndexScreen;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
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
		int buttonWidth = Math.max(110, this.getStringWidth(SCHEMATICINDEX$LABEL) + 20);
		int x = this.width - buttonWidth - 12;

		ButtonGeneric button = new ButtonGeneric(x, 30, buttonWidth, 20, SCHEMATICINDEX$LABEL);
		this.addButton(button, (pressed, mouseButton) -> {
			Minecraft client = Minecraft.getInstance();
			client.setScreen(new IndexScreen(this));
		});
	}
}
