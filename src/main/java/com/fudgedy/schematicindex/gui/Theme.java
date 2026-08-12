package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.IdentityHashMap;
import java.util.Map;

public final class Theme {
	public static final int ACCENT = 0xFF2A7A5B;
	public static final int ACCENT_PRESSED = 0xFF24684D;
	public static final int ACCENT_BRIGHT = 0xFF3FA87F;
	
	public static final int DOWNLOAD_FILL = 0xFF6FD9AE;
	public static final int ON_ACCENT = 0xFFFFFFFF;

	public static final int BACKDROP = 0xFF0F1114;
	public static final int SURFACE = 0xFF171A1E;
	public static final int SURFACE_CARD = 0xFF1E2227;
	public static final int SURFACE_ELEVATED = 0xFF23282E;
	public static final int HAIRLINE = 0xFF2C3238;

	public static final int TEXT = 0xFFF2F4F5;
	public static final int TEXT_MUTE = 0xFFA8B0B6;
	public static final int TEXT_ASH = 0xFF6E767C;

	public static final int SCRIM = 0x99000000;

	public static final int RAIL_TILE = 0xFF2A3037;
	public static final int RAIL_TILE_ACTIVE = 0xFF343B44;

	public static final int SKELETON = 0xFF262C33;
	public static final int SKELETON_SHINE = 0x14FFFFFF;

	public static final int RADIUS_PILL = 2;
	public static final int RADIUS_CARD = 3;
	public static final int RADIUS_MODAL = 4;

	private Theme() {
	}

	public static void roundedRect(GuiGraphics ctx, int x, int y, int width, int height, int radius, int color) {
		if (radius <= 0 || width < radius * 2 || height < radius * 2) {
			ctx.fill(x, y, x + width, y + height, color);
			return;
		}

		ctx.fill(x, y + radius, x + width, y + height - radius, color);

		for (int i = 0; i < radius; i++) {
			int inset = cornerInset(radius, i);
			ctx.fill(x + inset, y + i, x + width - inset, y + i + 1, color);
			ctx.fill(x + inset, y + height - i - 1, x + width - inset, y + height - i, color);
		}
	}

	public static void roundedOutline(GuiGraphics ctx, int x, int y, int width, int height, int radius, int color) {
		if (radius <= 0) {
			ctx.fill(x, y, x + width, y + 1, color);
			ctx.fill(x, y + height - 1, x + width, y + height, color);
			ctx.fill(x, y + 1, x + 1, y + height - 1, color);
			ctx.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
			return;
		}

		int topInset = cornerInset(radius, 0);
		ctx.fill(x + topInset, y, x + width - topInset, y + 1, color);
		ctx.fill(x + topInset, y + height - 1, x + width - topInset, y + height, color);
		ctx.fill(x, y + radius, x + 1, y + height - radius, color);
		ctx.fill(x + width - 1, y + radius, x + width, y + height - radius, color);

		for (int i = 0; i < radius; i++) {
			int inset = cornerInset(radius, i);
			ctx.fill(x + inset, y + i, x + inset + 1, y + i + 1, color);
			ctx.fill(x + width - inset - 1, y + i, x + width - inset, y + i + 1, color);
			ctx.fill(x + inset, y + height - i - 1, x + inset + 1, y + height - i, color);
			ctx.fill(x + width - inset - 1, y + height - i - 1, x + width - inset, y + height - i, color);
		}
	}

	private static int cornerInset(int radius, int row) {
		double dy = radius - row - 0.5D;
		double dx = Math.sqrt(Math.max(0.0D, radius * radius - dy * dy));
		return Math.max(0, radius - (int) Math.round(dx));
	}

	public static void text(GuiGraphics ctx, Font font, String value, int x, int y, int color) {
		ctx.drawString(font, value, x, y, color, false);
	}

	public static void textScaled(GuiGraphics ctx, Font font, String value, int x, int y, float scale, int color) {
		ctx.pose().pushMatrix();
		ctx.pose().translate((float) x, (float) y);
		ctx.pose().scale(scale, scale);
		ctx.drawString(font, value, 0, 0, color, false);
		ctx.pose().popMatrix();
	}

	public static String bold(String value) {
		return "§l" + value;
	}

	public static String clip(Font font, String value, int maxWidth) {
		if (font.width(value) <= maxWidth) {
			return value;
		}

		String ellipsis = "...";
		int room = maxWidth - font.width(ellipsis);

		if (room <= 0) {
			return ellipsis;
		}

		StringBuilder out = new StringBuilder();

		for (int i = 0; i < value.length(); i++) {
			if (font.width(out.toString() + value.charAt(i)) > room) {
				break;
			}

			out.append(value.charAt(i));
		}

		return out + ellipsis;
	}

	public static String clipBold(Font font, String value, int maxWidth) {
		if (font.width(bold(value)) <= maxWidth) {
			return value;
		}

		String ellipsis = "...";
		int room = maxWidth - font.width(bold(ellipsis));

		if (room <= 0) {
			return "";
		}

		StringBuilder out = new StringBuilder();

		for (int i = 0; i < value.length(); i++) {
			if (font.width(bold(out.toString() + value.charAt(i))) > room) {
				break;
			}

			out.append(value.charAt(i));
		}

		return out + ellipsis;
	}

	public static int boldFitCount(Font font, String value, int maxWidth) {
		for (int i = 0; i < value.length(); i++) {
			if (font.width(bold(value.substring(0, i + 1))) > maxWidth) {
				return i;
			}
		}

		return value.length();
	}

	public static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	public static int lighten(int color, float amount) {
		int alpha = color >>> 24;
		int red = (color >> 16) & 0xFF;
		int green = (color >> 8) & 0xFF;
		int blue = color & 0xFF;
		red += Math.round((255 - red) * amount);
		green += Math.round((255 - green) * amount);
		blue += Math.round((255 - blue) * amount);
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static final long HOVER_MS = 200L;
	private static final long PRESS_MS = 150L;
	private static final long POP_MS = 220L;
	public static final float HOVER_SCALE = 0.02F;
	public static final float PRESS_SCALE = 0.98F;
	
	public static final float POP_SCALE = 0.10F;

	private static final class Fx {
		float hover;
		long lastUpdate;
		long pressAt = -1L;
		long popAt = -1L;
	}

	private static final Map<Object, Fx> BUTTON_FX = new IdentityHashMap<>();

	public static float buttonHover(Object key, boolean hovered) {
		long now = Minecraft.getInstance() == null ? 0L : System.currentTimeMillis();
		Fx fx = BUTTON_FX.get(key);

		if (fx == null) {
			fx = new Fx();
			fx.hover = hovered ? 1.0F : 0.0F;
			fx.lastUpdate = now;
			BUTTON_FX.put(key, fx);
			return fx.hover;
		}

		float step = (now - fx.lastUpdate) / (float) HOVER_MS;
		fx.lastUpdate = now;
		float target = hovered ? 1.0F : 0.0F;
		fx.hover = fx.hover < target
				? Math.min(target, fx.hover + step)
				: Math.max(target, fx.hover - step);
		return fx.hover;
	}

	public static void buttonPress(Object key) {
		Fx fx = BUTTON_FX.computeIfAbsent(key, k -> new Fx());
		fx.pressAt = System.currentTimeMillis();
	}

	public static float buttonScale(Object key, float base) {
		Fx fx = BUTTON_FX.get(key);

		if (fx == null || fx.pressAt < 0L) {
			return base;
		}

		long age = System.currentTimeMillis() - fx.pressAt;

		if (age >= PRESS_MS) {
			fx.pressAt = -1L;
			return base;
		}

		float t = age / (float) PRESS_MS;
		float eased = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
		return PRESS_SCALE + (base - PRESS_SCALE) * eased;
	}

	public static void buttonPop(Object key) {
		Fx fx = BUTTON_FX.computeIfAbsent(key, k -> new Fx());
		fx.popAt = System.currentTimeMillis();
	}

	public static float popScale(Object key, float base) {
		Fx fx = BUTTON_FX.get(key);

		if (fx == null || fx.popAt < 0L) {
			return base;
		}

		long age = System.currentTimeMillis() - fx.popAt;

		if (age >= POP_MS) {
			fx.popAt = -1L;
			return base;
		}

		float t = age / (float) POP_MS;
		return base + POP_SCALE * (float) Math.sin(t * Math.PI);
	}

	public static void pushScale(GuiGraphics ctx, int x, int y, int width, int height, float scale) {
		float centreX = x + width / 2.0F;
		float centreY = y + height / 2.0F;
		ctx.pose().pushMatrix();
		ctx.pose().translate(centreX, centreY);
		ctx.pose().scale(scale, scale);
		ctx.pose().translate(-centreX, -centreY);
	}

	public static void pop(GuiGraphics ctx) {
		ctx.pose().popMatrix();
	}

	public static void image(GuiGraphics ctx, Identifier texture, int x, int y, int width, int height) {
		image(ctx, texture, x, y, width, height, 512, 288);
	}

	public static void image(GuiGraphics ctx, Identifier texture, int x, int y, int width, int height,
			int sourceWidth, int sourceHeight) {
		ctx.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, width, height,
				sourceWidth, sourceHeight, sourceWidth, sourceHeight);
	}

	public static void blueprintPlaceholder(GuiGraphics ctx, int x, int y, int width, int height) {
		ctx.fillGradient(x, y, x + width, y + height, 0xFF1B3A2F, BACKDROP);
		ctx.fill(x, y, x + width, y + 1, 0x14FFFFFF);
	}

	private static final String[] HEART_INNER = {
			"         ",
			"  XX XX  ",
			" XXXXXXX ",
			" XXXXXXX ",
			" XXXXXXX ",
			"  XXXXX  ",
			"   XXX   ",
			"    X    ",
			"         "
	};

	public static final int HEART_EMPTY = 0xFF8C959B;

	public static void arrow(GuiGraphics ctx, int x, int y, boolean left, int color) {
		for (int row = 0; row < 7; row++) {
			int width = Math.min(row, 6 - row) + 1;
			int rowX = left ? x + (4 - width) : x;
			ctx.fill(rowX, y + row, rowX + width, y + row + 1, color);
		}
	}

	public static void flag(GuiGraphics ctx, int x, int y, int size, int color) {
		int pole = Math.max(2, size / 12 + 1);
		ctx.fill(x, y, x + pole, y + size, color);

		int flagH = Math.max(3, size / 2);
		int flagW = Math.max(3, size / 2 + 1);

		for (int row = 0; row < flagH; row++) {
			int w = flagW - row * flagW / flagH;

			if (w <= 0) {
				break;
			}

			ctx.fill(x + pole, y + row, x + pole + w, y + row + 1, color);
		}
	}

	public static void cross(GuiGraphics ctx, int x, int y, int size, int color) {
		for (int i = 0; i < size; i++) {
			ctx.fill(x + i, y + i, x + i + 1, y + i + 1, color);
			ctx.fill(x + i, y + size - 1 - i, x + i + 1, y + size - i, color);
		}
	}

	public static void downloadGlyph(GuiGraphics ctx, int x, int y, int color) {
		ctx.fill(x + 2, y, x + 3, y + 2, color);
		ctx.fill(x, y + 2, x + 5, y + 3, color);
		ctx.fill(x + 1, y + 3, x + 4, y + 4, color);
		ctx.fill(x + 2, y + 4, x + 3, y + 5, color);
	}

	public static final int DOWNLOAD_GLYPH_WIDTH = 5;

	public static void click(float pitch) {
		if (!Settings.sounds()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();

		if (client != null && client.getSoundManager() != null) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch));
		}
	}

	public static void click() {
		click(1.0F);
	}

	public static void sound(SoundEvent event, float pitch, float volume) {
		if (!Settings.sounds()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();

		if (client != null && client.getSoundManager() != null) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
		}
	}

	public static void tab() {
		sound(SoundEvents.AMETHYST_CLUSTER_BREAK, 1.0F, 1.0F);
	}

	public static void success() {
		sound(SoundEvents.PLAYER_LEVELUP, 1.0F, 0.8F);
	}

	public static void failure() {
		sound(SoundEvents.VILLAGER_HURT, 1.0F, 1.0F);
	}

	public static void follow() {
		sound(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0F, 0.9F);
	}

	public static void like() {
		sound(SoundEvents.NOTE_BLOCK_HARP.value(), 1.0F, 0.9F);
	}

	public static void heartPopped(GuiGraphics ctx, int x, int y, boolean liked, long age) {
		float scale = 1.0F;

		if (age >= 0 && age < HEART_POP_MILLIS) {
			float progress = age / (float) HEART_POP_MILLIS;

			scale = 1.0F + 0.22F * (float) Math.sin(progress * Math.PI);
		}

		if (scale == 1.0F) {
			heart(ctx, x, y, liked);
			return;
		}

		float centreX = x + 4.5F;
		float centreY = y + 4.5F;
		ctx.pose().pushMatrix();
		ctx.pose().translate(centreX, centreY);
		ctx.pose().scale(scale, scale);
		ctx.pose().translate(-centreX, -centreY);
		heart(ctx, x, y, liked);
		ctx.pose().popMatrix();
	}

	public static final long HEART_POP_MILLIS = 220L;

	public static void heart(GuiGraphics ctx, int x, int y, boolean liked) {
		ctx.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("hud/heart/container"), x, y, 9, 9);

		if (liked) {
			ctx.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("hud/heart/full"), x, y, 9, 9);
			return;
		}

		for (int row = 0; row < HEART_INNER.length; row++) {
			String line = HEART_INNER[row];
			int runStart = -1;

			for (int column = 0; column <= line.length(); column++) {
				boolean filled = column < line.length() && line.charAt(column) == 'X';

				if (filled && runStart < 0) {
					runStart = column;
				} else if (!filled && runStart >= 0) {
					ctx.fill(x + runStart, y + row, x + column, y + row + 1, HEART_EMPTY);
					runStart = -1;
				}
			}
		}
	}
}
