package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
	private static final AtomicLong REVISION = new AtomicLong();
	private static final AtomicBoolean FETCHING = new AtomicBoolean();

	private Catalogue() {
	}

	public static State state() {
		return state;
	}

	public static List<SchematicEntry> posts() {
		return posts;
	}

	public static long revision() {
		return REVISION.get();
	}

	private static void setPosts(List<SchematicEntry> next) {
		List<SchematicEntry> previous = posts;
		posts = next;
		REVISION.incrementAndGet();
		notifyNewPosts(previous, next);
	}

	private static void notifyNewPosts(List<SchematicEntry> previous, List<SchematicEntry> next) {
		if (previous.isEmpty()) {
			return;
		}

		Set<String> known = new HashSet<>();

		for (SchematicEntry entry : previous) {
			known.add(entry.id());
		}

		for (SchematicEntry entry : next) {
			if (!known.contains(entry.id())) {
				Follows.notifyForPost(entry);
			}
		}
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

		if (!FETCHING.compareAndSet(false, true)) {
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
			} finally {
				FETCHING.set(false);
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
