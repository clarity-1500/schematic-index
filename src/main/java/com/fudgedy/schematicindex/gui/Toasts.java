package com.fudgedy.schematicindex.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bottom-left notification toasts, in the style Essential uses: a small rounded card that slides in
 * from the left edge, holds, then slides back out, with a thin timer bar depleting along the bottom.
 *
 * <p>Rendered in two places so a notification reaches the player wherever they are - over the
 * in-game HUD through {@code GuiHudMixin}, and over the Index screen from its own render. Only one of
 * those runs on any given frame (a screen hides the HUD), so there is no double draw.
 *
 * <p>Pushes come from the render thread today; the list is copy-on-write so a HUD frame reading it
 * while a push lands can never throw.
 */
public final class Toasts {
	private static final int MARGIN = 8;
	private static final int WIDTH = 176;
	private static final int GAP = 6;
	private static final long LIFETIME = 5200L;
	private static final long SLIDE = 260L;
	private static final int MAX_VISIBLE = 4;

	private static final List<Toast> ACTIVE = new CopyOnWriteArrayList<>();

	private record Toast(String title, String message, long spawnedAt, @Nullable ItemStack icon) {
	}

	private Toasts() {
	}

	/** Queue a toast. {@code icon} may be null for a text-only card. */
	public static void push(String title, String message, @Nullable ItemStack icon) {
		ACTIVE.add(new Toast(title, message, System.currentTimeMillis(), icon));

		// A hard backstop against a runaway burst; the visible cap is separate and lower.
		while (ACTIVE.size() > 16) {
			ACTIVE.remove(0);
		}
	}

	public static void clear() {
		ACTIVE.clear();
	}

	public static void render(GuiGraphics ctx) {
		if (ACTIVE.isEmpty()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();

		if (client == null || client.font == null || client.getWindow() == null) {
			return;
		}

		long now = System.currentTimeMillis();
		ACTIVE.removeIf(toast -> now - toast.spawnedAt() >= LIFETIME);

		Font font = client.font;
		int baseY = client.getWindow().getGuiScaledHeight() - MARGIN;
		int shown = 0;

		for (Toast toast : ACTIVE) {
			if (shown >= MAX_VISIBLE) {
				break;
			}

			int textLeft = toast.icon() != null ? 28 : 10;
			List<String> lines = wrap(font, toast.message(), WIDTH - textLeft - 10, 2);
			int cardHeight = 8 + font.lineHeight + 3 + Math.max(1, lines.size()) * (font.lineHeight + 1) + 6;

			baseY -= cardHeight;
			draw(ctx, font, toast, slideX(now - toast.spawnedAt()), baseY, cardHeight, textLeft, lines,
					now - toast.spawnedAt());
			baseY -= GAP;
			shown++;
		}
	}

	private static void draw(GuiGraphics ctx, Font font, Toast toast, int x, int y, int height,
			int textLeft, List<String> lines, long age) {
		Theme.roundedRect(ctx, x, y, WIDTH, height, Theme.RADIUS_CARD, 0xF01A1E23);
		Theme.roundedOutline(ctx, x, y, WIDTH, height, Theme.RADIUS_CARD, Theme.HAIRLINE);

		// Left accent stripe, then the item icon if there is one.
		ctx.fill(x + 2, y + 4, x + 4, y + height - 4, Theme.ACCENT);

		if (toast.icon() != null) {
			ctx.renderItem(toast.icon(), x + 8, y + (height - 16) / 2);
		}

		int tx = x + textLeft;
		Theme.text(ctx, font, Theme.bold(Theme.clipBold(font, toast.title(), WIDTH - textLeft - 8)),
				tx, y + 6, Theme.TEXT);

		int ly = y + 6 + font.lineHeight + 2;

		for (String line : lines) {
			Theme.text(ctx, font, line, tx, ly, Theme.TEXT_MUTE);
			ly += font.lineHeight + 1;
		}

		// Timer bar: depletes left to right over the toast's life.
		float remaining = Math.max(0.0F, 1.0F - age / (float) LIFETIME);
		int barWidth = Math.round((WIDTH - 8) * remaining);
		ctx.fill(x + 4, y + height - 3, x + 4 + barWidth, y + height - 2, Theme.ACCENT_BRIGHT);
	}

	/** Slide in from off the left edge, hold, then slide back out. */
	private static int slideX(long age) {
		int hidden = -(WIDTH + MARGIN + 4);

		if (age < SLIDE) {
			float t = age / (float) SLIDE;
			float eased = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
			return Math.round(hidden + (MARGIN - hidden) * eased);
		}

		if (age > LIFETIME - SLIDE) {
			float t = (age - (LIFETIME - SLIDE)) / (float) SLIDE;
			return Math.round(MARGIN + (hidden - MARGIN) * (t * t));
		}

		return MARGIN;
	}

	private static List<String> wrap(Font font, String text, int width, int maxLines) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (String word : text.split(" ")) {
			String candidate = current.isEmpty() ? word : current + " " + word;

			if (font.width(candidate) > width && !current.isEmpty()) {
				lines.add(current.toString());
				current = new StringBuilder(word);

				if (lines.size() == maxLines) {
					// Mark that there was more than fits, so a clipped message does not look complete.
					String last = lines.get(maxLines - 1);
					lines.set(maxLines - 1, Theme.clip(font, last + "...", width));
					return lines;
				}
			} else {
				current = new StringBuilder(candidate);
			}
		}

		if (!current.isEmpty() && lines.size() < maxLines) {
			lines.add(current.toString());
		}

		return lines;
	}
}
