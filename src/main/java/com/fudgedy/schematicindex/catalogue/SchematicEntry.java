package com.fudgedy.schematicindex.catalogue;

import java.util.List;

/**
 * One catalogue post. Mirrors what the index JSON will eventually carry, so swapping the mock
 * catalogue for a real backend does not change the GUI.
 *
 * @param title      the full schematic name, shown in the detail panel
 * @param thumbnailName the short name shown on the card - capped at upload to what fits a card, so a
 *                      long title can never break the card border
 * @param poster     the account that uploaded the post
 * @param designer   who actually built the design - often not the same person
 * @param imageCount how many gallery images the poster attached (first one is the thumbnail)
 * @param imageStart index of the first gallery image in the image source
 * @param schematicSlot which schematic the 3D preview renders - separate from the images, so an
 *                      uploaded post previews its own file rather than a stand-in
 * @param thumbnailUrl  the card image URL from the catalogue (null in the local beta)
 * @param imageUrls     the gallery image URLs from the catalogue (empty in the local beta)
 * @param fileUrl       the schematic download URL from the catalogue (null in the local beta)
 * @param fileHash      integrity/cache hash of the file (null in the local beta)
 * @param fileSize      the file size in bytes, or 0 if unknown
 * @param liked         whether the server says this device liked the post
 */
public record SchematicEntry(
		String id,
		String title,
		String thumbnailName,
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
		boolean downloaded,
		String thumbnailUrl,
		List<String> imageUrls,
		String fileUrl,
		String fileHash,
		long fileSize,
		boolean liked
) {
	/**
	 * A local/beta post with no catalogue URLs - keeps the original argument shape at call sites so the
	 * mock and upload form do not each have to spell out the server-only fields.
	 */
	public static SchematicEntry local(String id, String title, String thumbnailName, String poster,
			String designer, Category category, int sizeX, int sizeY, int sizeZ, int blockCount, int downloads,
			int likes, long postedAt, String description, int imageCount, int imageStart, int schematicSlot,
			boolean downloaded) {
		return new SchematicEntry(id, title, thumbnailName, poster, designer, category, sizeX, sizeY, sizeZ,
				blockCount, downloads, likes, postedAt, description, imageCount, imageStart, schematicSlot,
				downloaded, null, List.of(), null, null, 0L, false);
	}

	/** What the card shows: the short thumbnail name, falling back to the full title. */
	public String cardName() {
		return this.thumbnailName == null || this.thumbnailName.isBlank() ? this.title : this.thumbnailName;
	}

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

	/** The bounding-box volume in blocks: width x height x length. */
	public String volumeLabel() {
		return compact(this.sizeX * this.sizeY * this.sizeZ);
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
