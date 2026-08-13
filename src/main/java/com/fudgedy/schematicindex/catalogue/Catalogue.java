package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public final class Catalogue {
	public enum State {
		LOADING,
		READY,
		OFFLINE
	}

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
	private static volatile long revision;

	private Catalogue() {
	}

	public static State state() {
		return state;
	}

	public static List<SchematicEntry> posts() {
		return posts;
	}

	public static long revision() {
		return revision;
	}

	private static void setPosts(List<SchematicEntry> next) {
		posts = next;
		revision++;
	}

	public static void ensureLoaded() {
		if (started) {
			refresh(true);
			return;
		}

		started = true;
		refresh(false);
	}

	public static void refresh() {
		refresh(false);
	}

	private static void refresh(boolean soft) {
		if (!Backend.configured()) {
			setPosts(List.of());
			state = State.OFFLINE;
			return;
		}

		if (!soft) {
			state = State.LOADING;
		}

		Thread worker = new Thread(() -> {
			try {
				List<SchematicEntry> fetched = fetchAll();
				setPosts(fetched);
				NewsFeed.refresh();
				state = State.READY;
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.info("Catalogue fetch failed: {}", e.toString());

				if (!soft) {
					state = State.OFFLINE;
				}
			}
		}, "schematicindex-catalogue");
		worker.setDaemon(true);
		worker.start();
	}

	private static List<SchematicEntry> fetchAll() {
		List<SchematicEntry> all = new ArrayList<>();
		String cursor = null;

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
