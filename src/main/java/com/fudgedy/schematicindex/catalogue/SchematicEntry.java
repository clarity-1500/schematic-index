package com.fudgedy.schematicindex.catalogue;

import java.util.List;

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
		boolean liked,
		double trendScore,
		int views
) {
	public static SchematicEntry local(String id, String title, String thumbnailName, String poster,
			String designer, Category category, int sizeX, int sizeY, int sizeZ, int blockCount, int downloads,
			int likes, long postedAt, String description, int imageCount, int imageStart, int schematicSlot,
			boolean downloaded) {
		return new SchematicEntry(id, title, thumbnailName, poster, designer, category, sizeX, sizeY, sizeZ,
				blockCount, downloads, likes, postedAt, description, imageCount, imageStart, schematicSlot,
				downloaded, null, List.of(), null, null, 0L, false, 0.0, 0);
	}

	public String cardName() {
		return this.thumbnailName == null || this.thumbnailName.isBlank() ? this.title : this.thumbnailName;
	}

	public String dimensionsLabel() {
		return this.sizeX + "x" + this.sizeY + "x" + this.sizeZ;
	}

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

	public String volumeLabel() {
		return compact(this.sizeX * this.sizeY * this.sizeZ);
	}

	public String downloadsLabel() {
		return compact(this.downloads);
	}

	public String likesLabel() {
		return compact(this.likes);
	}

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
