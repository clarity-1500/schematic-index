package com.fudgedy.schematicindex.mixin;

import com.fudgedy.schematicindex.gui.Toasts;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the mod's notification toasts on top of the in-game HUD.
 *
 * <p>This mod has no Fabric API dependency, and the HUD render event is deprecated in this version
 * anyway, so a tail inject on the vanilla HUD render is the most direct hook. A screen hides the HUD,
 * so while the Index screen is open the toasts are drawn by the screen itself instead - the two never
 * fire on the same frame.
 */
@Mixin(Gui.class)
public class GuiHudMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void schematicindex$renderToasts(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo info) {
		Toasts.render(graphics);
	}
}
