package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.UpdateGate;
import com.fudgedy.schematicindex.update.ModUpdater;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class UpdateScreen extends Screen {
	private final @Nullable Screen parent;
	private final int[] updateRect = new int[4];
	private final int[] skipRect = new int[4];
	private volatile String status = "";
	private boolean busy;

	public UpdateScreen(@Nullable Screen parent) {
		super(Component.literal("Update Required"));
		this.parent = parent;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return !this.busy;
	}

	@Override
	public void onClose() {
		if (this.busy) {
			return;
		}

		UpdateGate.dismiss();
		this.minecraft.setScreen(this.parent);
	}

	@Override
	public void render(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		ctx.fill(0, 0, this.width, this.height, Theme.BACKDROP);

		int cardWidth = 328;
		int cardHeight = 168;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		int pad = 16;
		int cx = x + cardWidth / 2;

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_ELEVATED);

		String title = Theme.bold("Update Required");
		Theme.text(ctx, this.font, title, cx - this.font.width(title) / 2, y + pad, Theme.TEXT);

		int line = y + pad + 18;
		String body = UpdateGate.message().isBlank()
				? "The Schematic Index needs to update to stay in sync with the server."
				: UpdateGate.message();

		for (String row : this.wrap(body, cardWidth - pad * 2)) {
			Theme.text(ctx, this.font, row, x + pad, line, Theme.TEXT_MUTE);
			line += this.font.lineHeight + 2;
		}

		line += 3;
		ModUpdater.Release release = UpdateGate.release();
		String versions = "Current " + UpdateGate.currentVersion()
				+ (release != null ? "   to   " + release.version() : "");
		Theme.text(ctx, this.font, versions, x + pad, line, Theme.TEXT_ASH);
		line += this.font.lineHeight + 6;

		String disclaimer = "Updating downloads the latest version and restarts Minecraft.";

		for (String row : this.wrap(disclaimer, cardWidth - pad * 2)) {
			Theme.text(ctx, this.font, row, x + pad, line, Theme.TEXT_ASH);
			line += this.font.lineHeight + 1;
		}

		if (!this.status.isEmpty()) {
			line += 3;

			for (String row : this.wrap(this.status, cardWidth - pad * 2)) {
				Theme.text(ctx, this.font, row, x + pad, line, Theme.ACCENT_BRIGHT);
				line += this.font.lineHeight + 1;
			}
		}

		int buttonHeight = 18;
		int buttonY = y + cardHeight - pad - buttonHeight;
		int gap = 8;
		int buttonWidth = (cardWidth - pad * 2 - gap) / 2;

		this.skipRect[0] = x + pad;
		this.skipRect[1] = buttonY;
		this.skipRect[2] = buttonWidth;
		this.skipRect[3] = buttonHeight;
		this.updateRect[0] = x + pad + buttonWidth + gap;
		this.updateRect[1] = buttonY;
		this.updateRect[2] = buttonWidth;
		this.updateRect[3] = buttonHeight;

		this.drawButton(ctx, this.skipRect, "Skip", mouseX, mouseY, false);
		this.drawButton(ctx, this.updateRect, this.busy ? "Updating..." : "Update", mouseX, mouseY, true);
	}

	private void drawButton(GuiGraphics ctx, int[] rect, String label, int mouseX, int mouseY, boolean accent) {
		boolean hovered = !this.busy && Theme.inside(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3]);
		int base = accent ? Theme.ACCENT : Theme.SURFACE_CARD;
		int fill = this.busy && accent ? Theme.SURFACE_CARD : (hovered ? Theme.lighten(base, 0.10F) : base);
		Theme.roundedRect(ctx, rect[0], rect[1], rect[2], rect[3], Theme.RADIUS_PILL, fill);

		int color = accent && !this.busy ? Theme.ON_ACCENT : Theme.TEXT;
		String bold = Theme.bold(label);
		Theme.text(ctx, this.font, bold, rect[0] + (rect[2] - this.font.width(bold)) / 2,
				rect[1] + (rect[3] - this.font.lineHeight) / 2 + 1, color);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.busy) {
			return true;
		}

		double mouseX = event.x();
		double mouseY = event.y();

		if (Theme.inside(mouseX, mouseY, this.skipRect[0], this.skipRect[1], this.skipRect[2], this.skipRect[3])) {
			Theme.click(0.9F);
			this.onClose();
			return true;
		}

		if (Theme.inside(mouseX, mouseY, this.updateRect[0], this.updateRect[1], this.updateRect[2], this.updateRect[3])) {
			Theme.click(1.1F);
			this.startUpdate();
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	private void startUpdate() {
		ModUpdater.Release release = UpdateGate.release();

		if (release == null) {
			this.status = "Update is not available yet. Please update from Modrinth.";
			return;
		}

		this.busy = true;
		this.status = "Starting update...";
		Thread worker = new Thread(() -> ModUpdater.install(release, value -> this.status = value),
				"schematicindex-update");
		worker.setDaemon(true);
		worker.start();
	}

	private List<String> wrap(String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (String word : text.split(" ")) {
			String trial = current.isEmpty() ? word : current + " " + word;

			if (this.font.width(trial) > maxWidth && !current.isEmpty()) {
				lines.add(current.toString());
				current = new StringBuilder(word);
			} else {
				current = new StringBuilder(trial);
			}
		}

		if (!current.isEmpty()) {
			lines.add(current.toString());
		}

		return lines;
	}
}
