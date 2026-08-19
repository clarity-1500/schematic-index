package com.fudgedy.schematicindex.mixin;

import com.fudgedy.schematicindex.UpdateGate;
import com.fudgedy.schematicindex.gui.UpdateScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
	@Inject(method = "init", at = @At("RETURN"))
	private void schematicindex$maybePromptUpdate(CallbackInfo info) {
		// Only the hard lock takes over the title screen. A notify-only update (the lock
		// opted out in Settings) surfaces inside the mod screen instead, never here.
		if (!UpdateGate.locked()) {
			return;
		}

		Screen self = (Screen) (Object) this;
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (UpdateGate.locked() && client.screen == self) {
				client.setScreen(new UpdateScreen(self));
			}
		});
	}
}
