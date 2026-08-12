package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Tracks whether the catalogue is reachable.
 *
 * <p>There is no index server yet, so by default the mod runs on {@link MockCatalogue} and reports
 * itself ready. Point {@code -Dschematicindex.index=<url>} at something and it will actually probe
 * that address on a worker thread, which is how the offline state can be seen (and tested - aim it
 * at a URL that does not resolve).
 *
 * <p>The GUI only ever reads {@link #state()}, so swapping this for the real fetch later does not
 * touch the screen code.
 */
public final class Catalogue {
	public enum State {
		LOADING,
		READY,
		OFFLINE
	}

	private static volatile State state = State.LOADING;
	private static volatile boolean started;

	private Catalogue() {
	}

	public static State state() {
		return state;
	}

	/** Kicks off a connection check the first time the library is opened. */
	public static void ensureLoaded() {
		if (started) {
			return;
		}

		started = true;
		refresh();
	}

	public static void refresh() {
		String index = System.getProperty("schematicindex.index");
		state = State.LOADING;

		if (index == null || index.isBlank()) {
			// No backend configured: the mock catalogue is the source, and it is always available.
			state = State.READY;
			return;
		}

		Thread worker = new Thread(() -> {
			try (HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(4))
					.build()) {
				HttpRequest request = HttpRequest.newBuilder(URI.create(index))
						.timeout(Duration.ofSeconds(6))
						.method("HEAD", HttpRequest.BodyPublishers.noBody())
						.build();
				HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
				state = response.statusCode() < 400 ? State.READY : State.OFFLINE;
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.info("Catalogue unreachable at {}: {}", index, e.toString());
				state = State.OFFLINE;
			}
		}, "schematicindex-connect");
		worker.setDaemon(true);
		worker.start();
	}
}
