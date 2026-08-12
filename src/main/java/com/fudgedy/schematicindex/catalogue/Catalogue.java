package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The catalogue of posts, fetched from the backend.
 *
 * <p>On open (or refresh) it pulls every visible post, page by page, on a worker thread and holds the
 * result. The GUI reads {@link #state()} for the loading/offline chrome and {@link #posts()} for the
 * grid, which then does its own category/search/sort client-side. With no server address configured
 * the state is simply {@link State#OFFLINE}.
 */
public final class Catalogue {
	public enum State {
		LOADING,
		READY,
		OFFLINE
	}

	/** Sort order for the grid. Applied client-side over {@link #posts()}. */
	public enum Sort {
		NEWEST("Newest"),
		DOWNLOADS("Most downloaded"),
		LIKES("Most liked");

		private final String label;

		Sort(String label) {
			this.label = label;
		}

		public String label() {
			return this.label;
		}

		public Sort next() {
			Sort[] all = values();
			return all[(this.ordinal() + 1) % all.length];
		}
	}

	private static volatile State state = State.LOADING;
	private static volatile boolean started;
	private static volatile List<SchematicEntry> posts = List.of();

	private Catalogue() {
	}

	public static State state() {
		return state;
	}

	public static List<SchematicEntry> posts() {
		return posts;
	}

	/** Kicks off the first fetch when the library is opened. */
	public static void ensureLoaded() {
		if (started) {
			return;
		}

		started = true;
		refresh();
	}

	public static void refresh() {
		state = State.LOADING;

		if (!Backend.configured()) {
			posts = List.of();
			state = State.OFFLINE;
			return;
		}

		Thread worker = new Thread(() -> {
			try {
				posts = fetchAll();
				NewsFeed.refresh();
				state = State.READY;
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.info("Catalogue fetch failed: {}", e.toString());
				state = State.OFFLINE;
			}
		}, "schematicindex-catalogue");
		worker.setDaemon(true);
		worker.start();
	}

	private static List<SchematicEntry> fetchAll() {
		List<SchematicEntry> all = new ArrayList<>();
		String cursor = null;

		// Page through the whole catalogue, capped so a broken server can never loop forever.
		for (int page = 0; page < 40; page++) {
			String path = "/index?limit=60" + (cursor == null ? "" : "&cursor=" + Backend.encode(cursor));
			JsonObject body = Backend.getJson(path);

			if (body == null) {
				if (page == 0) {
					throw new IllegalStateException("index unreachable");
				}

				break;
			}

			for (JsonElement element : body.getAsJsonArray("posts")) {
				all.add(Backend.parsePost(element.getAsJsonObject()));
			}

			if (body.has("nextCursor") && !body.get("nextCursor").isJsonNull()) {
				cursor = body.get("nextCursor").getAsString();
			} else {
				break;
			}
		}

		return all;
	}
}
