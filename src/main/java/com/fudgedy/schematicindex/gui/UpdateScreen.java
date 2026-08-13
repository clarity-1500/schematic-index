package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.UpdateGate;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class UpdateScreen extends Screen {
	private static final String MODRINTH_URL = "https://modrinth.com/project/the-schematic-index";
	private static final String LINK_LABEL = "modrinth.com/project/the-schematic-index";
	private static final float TITLE_SCALE = 1.6F;

	private final @Nullable Screen parent;
	private final int[] skipRect = new int[4];
	private final int[] linkRect = new int[4];

	public UpdateScreen(@Nullable Screen parent) {
		super(Component.literal("Update Required"));
		this.parent = parent;
	}

	@Override
	public void onClose() {
		UpdateGate.dismiss();
		this.minecraft.setScreen(this.parent);
	}

	@Override
	public void render(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		ctx.fill(0, 0, this.width, this.height, Theme.BACKDROP);

		int pad = 16;
		int cardWidth = 336;
		int innerWidth = cardWidth - pad * 2;
		int buttonHeight = 18;
		int titleHeight = Math.round(this.font.lineHeight * TITLE_SCALE);
		int lineHeight = this.font.lineHeight;

		String body = UpdateGate.message().isBlank()
				? "Please install the latest mod version from Modrinth."
				: UpdateGate.message();
		String note = "Pressing \"Skip\" disables the mod until the latest version is installed.";
		String versions = "You are running version " + UpdateGate.currentVersion();

		List<String> bodyLines = this.wrap(body, innerWidth);
		List<String> noteLines = this.wrap(note, innerWidth);

		int cardHeight = pad
				+ titleHeight + 6
				+ lineHeight + 10
				+ bodyLines.size() * (lineHeight + 2)
				+ 5 + lineHeight
				+ 6 + lineHeight
				+ 8 + noteLines.size() * (lineHeight + 1)
				+ 12 + buttonHeight
				+ pad;

		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		int cx = x + cardWidth / 2;

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_ELEVATED);

		int line = y + pad;

		String name = Theme.bold("The Schematic Index");
		int nameWidth = Math.round(this.font.width(name) * TITLE_SCALE);
		Theme.textScaled(ctx, this.font, name, cx - nameWidth / 2, line, TITLE_SCALE, Theme.TEXT);
		line += titleHeight + 6;

		String heading = "Update Required";
		Theme.text(ctx, this.font, heading, cx - this.font.width(heading) / 2, line, Theme.ACCENT_BRIGHT);
		line += lineHeight + 10;

		for (String row : bodyLines) {
			Theme.text(ctx, this.font, row, cx - this.font.width(row) / 2, line, Theme.TEXT_MUTE);
			line += lineHeight + 2;
		}

		line += 5;
		int linkWidth = this.font.width(LINK_LABEL);
		int linkX = cx - linkWidth / 2;
		this.linkRect[0] = linkX;
		this.linkRect[1] = line - 1;
		this.linkRect[2] = linkWidth;
		this.linkRect[3] = lineHeight + 2;
		boolean linkHovered = Theme.inside(mouseX, mouseY, this.linkRect[0], this.linkRect[1], this.linkRect[2], this.linkRect[3]);
		int linkColor = linkHovered ? Theme.lighten(Theme.ACCENT_BRIGHT, 0.25F) : Theme.ACCENT_BRIGHT;
		Theme.text(ctx, this.font, LINK_LABEL, linkX, line, linkColor);
		ctx.fill(linkX, line + lineHeight, linkX + linkWidth, line + lineHeight + 1, linkColor);
		line += lineHeight + 6;

		Theme.text(ctx, this.font, versions, cx - this.font.width(versions) / 2, line, Theme.TEXT_ASH);
		line += lineHeight + 8;

		for (String row : noteLines) {
			Theme.text(ctx, this.font, row, cx - this.font.width(row) / 2, line, Theme.TEXT_ASH);
			line += lineHeight + 1;
		}

		int buttonY = y + cardHeight - pad - buttonHeight;
		this.skipRect[0] = x + pad;
		this.skipRect[1] = buttonY;
		this.skipRect[2] = innerWidth;
		this.skipRect[3] = buttonHeight;

		boolean hovered = Theme.inside(mouseX, mouseY, this.skipRect[0], this.skipRect[1], this.skipRect[2], this.skipRect[3]);
		int fill = hovered ? Theme.lighten(Theme.SURFACE_CARD, 0.10F) : Theme.SURFACE_CARD;
		Theme.roundedRect(ctx, this.skipRect[0], this.skipRect[1], this.skipRect[2], this.skipRect[3], Theme.RADIUS_PILL, fill);
		String skip = Theme.bold("Skip");
		Theme.text(ctx, this.font, skip, cx - this.font.width(skip) / 2,
				this.skipRect[1] + (this.skipRect[3] - lineHeight) / 2 + 1, Theme.TEXT);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();

		if (Theme.inside(mouseX, mouseY, this.linkRect[0], this.linkRect[1], this.linkRect[2], this.linkRect[3])) {
			this.openModrinth();
			return true;
		}

		if (Theme.inside(mouseX, mouseY, this.skipRect[0], this.skipRect[1], this.skipRect[2], this.skipRect[3])) {
			Theme.click(0.9F);
			this.onClose();
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	private void openModrinth() {
		Theme.click(1.0F);
		ConfirmLinkScreen.confirmLinkNow(this, MODRINTH_URL, true);
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
