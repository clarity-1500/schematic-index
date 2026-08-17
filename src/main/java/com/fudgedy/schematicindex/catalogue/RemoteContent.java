package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.fudgedy.schematicindex.Settings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// Content the mod pulls from the server at runtime so it can change without a new
// release: the credits list, an announcement banner, the terms text and outbound links.
public final class RemoteContent {
	public record Credit(String username, String nickname, String role, String description, String avatarUrl) {
		public String displayName() {
			return nickname != null && !nickname.isBlank() ? nickname : username;
		}
	}

	public record Announcement(String id, String title, String tag, String description, String color) {
	}

	public record Terms(int version, String body) {
	}

	public record Link(String url, String iconUrl, String label) {
	}

	public record Partner(String url, String iconUrl, String name) {
	}

	private static volatile List<Credit> credits = List.of();
	private static volatile @Nullable Announcement announcement;
	private static volatile @Nullable Terms terms;
	private static volatile List<Link> links = List.of();
	private static volatile List<Partner> partners = List.of();
	private static volatile String discord = "";
	private static volatile boolean loaded;

	private static final java.util.concurrent.atomic.AtomicBoolean polling = new java.util.concurrent.atomic.AtomicBoolean();

	private RemoteContent() {
	}

	public static List<Credit> credits() {
		return credits;
	}

	public static @Nullable Announcement announcement() {
		return announcement;
	}

	public static @Nullable Terms terms() {
		return terms;
	}

	public static List<Link> links() {
		return links;
	}

	public static List<Partner> partners() {
		return partners;
	}

	public static String discord() {
		return discord;
	}

	public static boolean loaded() {
		return loaded;
	}

	// Refresh on a background thread; used to poll while a menu is open so new
	// content (like an announcement) appears without waiting for a catalogue reload.
	public static void pollAsync() {
		if (!polling.compareAndSet(false, true)) {
			return;
		}

		Thread thread = new Thread(() -> {
			try {
				refresh();
			} finally {
				polling.set(false);
			}
		}, "schematicindex-content-poll");
		thread.setDaemon(true);
		thread.start();
	}

	public static void refresh() {
		JsonObject body = Backend.getJson("/content");

		if (body == null) {
			return;
		}

		try {
			List<Credit> list = new ArrayList<>();

			if (body.has("credits") && body.get("credits").isJsonArray()) {
				for (JsonElement element : body.getAsJsonArray("credits")) {
					JsonObject o = element.getAsJsonObject();
					list.add(new Credit(s(o, "username"), s(o, "nickname"), s(o, "role"), s(o, "description"), s(o, "avatarUrl")));
				}
			}

			credits = list;

			if (body.has("announcement") && body.get("announcement").isJsonObject()) {
				JsonObject a = body.getAsJsonObject("announcement");
				announcement = new Announcement(s(a, "id"), s(a, "title"), s(a, "tag"), s(a, "description"), s(a, "color"));
			} else {
				announcement = null;
			}

			if (body.has("terms") && body.get("terms").isJsonObject()) {
				JsonObject t = body.getAsJsonObject("terms");
				int version = t.has("version") && !t.get("version").isJsonNull() ? t.get("version").getAsInt() : 1;
				String termsBody = s(t, "body");

				if (!termsBody.isBlank()) {
					terms = new Terms(version, termsBody);
					Settings.cacheTerms(version, termsBody);
				}
			}

			if (body.has("links") && body.get("links").isJsonArray()) {
				List<Link> linkList = new ArrayList<>();

				for (JsonElement element : body.getAsJsonArray("links")) {
					JsonObject o = element.getAsJsonObject();
					String url = s(o, "url");

					if (!url.isBlank()) {
						linkList.add(new Link(url, s(o, "iconUrl"), s(o, "label")));
					}
				}

				links = linkList;
			}

			if (body.has("partners") && body.get("partners").isJsonArray()) {
				List<Partner> partnerList = new ArrayList<>();

				for (JsonElement element : body.getAsJsonArray("partners")) {
					JsonObject o = element.getAsJsonObject();
					String url = s(o, "url");

					if (!url.isBlank()) {
						partnerList.add(new Partner(url, s(o, "iconUrl"), s(o, "name")));
					}
				}

				partners = partnerList;
			}

			discord = s(body, "discord");
			loaded = true;
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("Content parse failed", e);
		}
	}

	private static String s(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}
}
