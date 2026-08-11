package com.fudgedy.schematicindex.catalogue;

/**
 * One catalogue post. Mirrors what the index JSON will eventually carry, so swapping the mock
 * catalogue for a real backend does not change the GUI.
 *
 * @param poster     the account that uploaded the post
 * @param designer   who actually built the design - often not the same person
 * @param imageCount how many gallery images the poster attached (first one is the thumbnail)
 * @param imageStart index of the first gallery image in the image source
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
		String uploaded,
		String description,
		int imageCount,
		int imageStart,
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
}
