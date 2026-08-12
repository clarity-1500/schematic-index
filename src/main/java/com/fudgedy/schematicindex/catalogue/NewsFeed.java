package com.fudgedy.schematicindex.catalogue;

import java.util.List;

/**
 * Stand-in for the index's news and changelog. Deterministic, so the tab looks the same every
 * launch. Replace {@link #entries()} with the parsed news JSON later - nothing in the GUI reads
 * anything else.
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

	private static final List<Entry> ENTRIES = List.of(
			new Entry("v0.4", "Save, follow and download", "Aug 2026", List.of(
					"Save for later keeps a post in the Saved tab without liking it.",
					"Follow a creator from any post to get a toast the moment they publish something new.",
					"The Download button now fills with real transfer progress, and downloads land in your "
							+ "schematics folder automatically - change it any time in Settings."), true),
			new Entry("v0.3", "3D preview, layer by layer", "Aug 2026", List.of(
					"Every post renders in real block textures. Drag to orbit, scroll to zoom, and zoom "
							+ "straight through the walls to inspect an enclosed build from inside.",
					"A new layer slider peels the roof off so you can read interiors without moving the camera."), false),
			new Entry("v0.2", "Textured previews", "Jul 2026", List.of(
					"Previews moved from flat colours to the real block models and textures, with proper "
							+ "tinting for leaves, grass and water."), false),
			new Entry("News", "Welcome to The Schematic Index", "Jul 2026", List.of(
					"A community catalogue of DonutSMP schematics - farms, contraptions, regears, stashes "
							+ "and mega builds - browsable in game, straight from Litematica.",
					"Uploads are invite only for now to keep the catalogue clean. Ask an existing uploader "
							+ "for an access code."), false)
	);

	private NewsFeed() {
	}

	public static List<Entry> entries() {
		return ENTRIES;
	}
}
