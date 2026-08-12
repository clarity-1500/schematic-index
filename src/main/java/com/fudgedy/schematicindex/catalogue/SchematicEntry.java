package com.fudgedy.schematicindex.catalogue;

/**
 * One catalogue post. Mirrors what the index JSON will eventually carry, so swapping the mock
 * catalogue for a real backend does not change the GUI.
 *
 * @param poster     the account that uploaded the post
 * @param designer   who actually built the design - often not the same person
 * @param imageCount how many gallery images the poster attached (first one is the thumbnail)
 * @param imageStart index of the first gallery image in the image source
 * @param schematicSlot which schematic the 3D preview renders - separate from the images, so an
 *                      uploaded post previews its own file rather than a stand-in
 */
public record SchematicEntry(
		String id,
		String title,
		String poster,
		String designer,
		Category category,
		int sizeX,
		int sizeY,
		int sizeZ,
		int blockCount,
		int downloads,
		int likes,
		long postedAt,
		String description,
		int imageCount,
		int imageStart,
		int schematicSlot,
		boolean downloaded
) {
	public String dimensionsLabel() {
		return this.sizeX + "x" + this.sizeY + "x" + this.sizeZ;
	}

	/** 12345 -> "12.3k". Keeps card metadata to one short line. */
	public static String compact(int value) {
		if (value < 1000) {
			return Integer.toString(value);
		}

		if (value < 1_000_000) {
			double thousands = value / 1000.0D;
			return thousands < 10.0D
					? String.format("%.1fk", thousands)
					: Math.round(thousands) + "k";
		}

		return String.format("%.1fM", value / 1_000_000.0D);
	}

	public String blockCountLabel() {
		return compact(this.blockCount);
	}

	public String downloadsLabel() {
		return compact(this.downloads);
	}

	public String likesLabel() {
		return compact(this.likes);
	}

	/** "just now", "10m ago", "3h ago", "5d ago" - how the post's age reads in the corner of a card. */
	public String agoLabel() {
		long minutes = Math.max(0L, System.currentTimeMillis() - this.postedAt) / 60_000L;

		if (minutes < 1L) {
			return "just now";
		}

		if (minutes < 60L) {
			return minutes + "m ago";
		}

		long hours = minutes / 60L;

		if (hours < 24L) {
			return hours + "h ago";
		}

		long days = hours / 24L;

		if (days < 30L) {
			return days + "d ago";
		}

		long months = days / 30L;
		return months < 12L ? months + "mo ago" : (months / 12L) + "y ago";
	}
}
