package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Toasts {
	private static final int MARGIN = 8;
	private static final int WIDTH = 190;
	private static final int GAP = 6;

	private static final int BAR = 4;
	private static final long LIFETIME = 5200L;
	private static final long SLIDE = 260L;
	private static final int MAX_VISIBLE = 4;

	private static final List<Toast> ACTIVE = new CopyOnWriteArrayList<>();

	private record Toast(String title, String message, long spawnedAt, @Nullable ItemStack icon,
			@Nullable String avatarUrl) {
	}

	private Toasts() {
	}

	public static void push(String title, String message, @Nullable ItemStack icon) {
		add(new Toast(title, message, System.currentTimeMillis(), icon, null));
	}

	public static void pushAvatar(String title, String message, String avatarUrl) {
		add(new Toast(title, message, System.currentTimeMillis(), null, avatarUrl));
	}

	private static void add(Toast toast) {
		if (!Settings.toasts()) {
			return;
		}

		ACTIVE.add(toast);

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

			int textLeft = toast.icon() != null || toast.avatarUrl() != null ? 30 : 14;
			List<String> lines = wrap(font, toast.message(), WIDTH - textLeft - 10, 2);
			int cardHeight = 11 + font.lineHeight + 4 + Math.max(1, lines.size()) * (font.lineHeight + 1) + 9;

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

		ctx.fill(x + 1, y + 1, x + 1 + BAR, y + height - 1, Theme.ACCENT);

		if (toast.avatarUrl() != null) {
			int size = 18;
			int ax = x + BAR + 6;
			int ay = y + (height - size) / 2;
			Identifier avatar = ImageStore.avatar(toast.avatarUrl());

			if (avatar != null) {
				Theme.image(ctx, avatar, ax, ay, size, size, 64, 64);
			} else {
				Theme.roundedRect(ctx, ax, ay, size, size, 4, Theme.SURFACE_ELEVATED);
			}
		} else if (toast.icon() != null) {
			ctx.renderItem(toast.icon(), x + BAR + 6, y + (height - 16) / 2);
		}

		int tx = x + textLeft;
		Theme.text(ctx, font, Theme.bold(Theme.clipBold(font, toast.title(), WIDTH - textLeft - 8)),
				tx, y + 8, Theme.TEXT);

		int ly = y + 8 + font.lineHeight + 3;

		for (String line : lines) {
			Theme.text(ctx, font, line, tx, ly, Theme.TEXT_MUTE);
			ly += font.lineHeight + 1;
		}

		float remaining = Math.max(0.0F, 1.0F - age / (float) LIFETIME);
		int trackLeft = x + BAR + 4;
		int barWidth = Math.round((x + WIDTH - 4 - trackLeft) * remaining);
		ctx.fill(trackLeft, y + height - 3, trackLeft + barWidth, y + height - 2, Theme.ACCENT_BRIGHT);
	}

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
