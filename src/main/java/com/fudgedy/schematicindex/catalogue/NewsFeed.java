package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The news / changelog, fetched from the backend's {@code /news} endpoint. {@link Catalogue} refreshes
 * it alongside the catalogue, on the same worker thread, so the News tab just reads {@link #entries()}.
 */
public final class NewsFeed {
	/**
	 * One news or changelog post.
	 *
	 * @param badge     the small pill on the card - a version for a release, a word for news
	 * @param title     the headline
	 * @param when      a short human date, kept as text so it never drifts against the clock
	 * @param lines     body paragraphs, each wrapped to the card on its own
	 * @param highlight fill the badge with the accent - use for the newest or most important post
	 */
	public record Entry(String badge, String title, String when, List<String> lines, boolean highlight) {
	}

	private static volatile List<Entry> entries = List.of();

	private NewsFeed() {
	}

	public static List<Entry> entries() {
		return entries;
	}

	/** Fetches the latest news. Runs on the catalogue worker; keeps the old list if the fetch fails. */
	public static void refresh() {
		JsonObject body = Backend.getJson("/news");

		if (body == null) {
			return;
		}

		try {
			List<Entry> list = new ArrayList<>();

			for (JsonElement element : body.getAsJsonArray("news")) {
				list.add(Backend.parseNews(element.getAsJsonObject()));
			}

			entries = list;
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("News parse failed", e);
		}
	}
}
