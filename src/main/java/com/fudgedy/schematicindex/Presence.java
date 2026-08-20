package com.fudgedy.schematicindex;

import com.fudgedy.schematicindex.catalogue.Backend;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends an anonymous presence heartbeat shortly after launch and every 10 minutes while the game
 * runs. The server counts distinct installs ("total users") and installs seen within the last
 * window ("online now"). Each beat carries only the anonymous device token and the mod version,
 * and {@link Backend#heartbeat(String)} no-ops until the user has accepted the terms - so nothing
 * is sent before consent, and revoking consent stops the beats.
 */
public final class Presence {
	private static final long INTERVAL_MINUTES = 10;

	private static ScheduledExecutorService scheduler;

	private Presence() {
	}

	public static synchronized void start() {
		if (scheduler != null) {
			return;
		}

		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "schematicindex-presence");
			thread.setDaemon(true);
			return thread;
		});

		// First beat a few seconds in (once the version is resolvable), then on a fixed 10-minute
		// cadence for as long as the game is open.
		scheduler.scheduleAtFixedRate(Presence::beat, 5, INTERVAL_MINUTES * 60, TimeUnit.SECONDS);
	}

	/**
	 * Fire an immediate beat off-thread. Called the moment the user accepts the terms so a fresh
	 * install shows up in the totals right away instead of waiting for the next interval.
	 */
	public static void beatNow() {
		ScheduledExecutorService current = scheduler;

		if (current != null) {
			current.execute(Presence::beat);
		}
	}

	private static void beat() {
		try {
			Backend.heartbeat(UpdateGate.currentVersion());
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("Presence beat failed", e);
		}
	}
}
