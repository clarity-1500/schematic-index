package com.fudgedy.schematicindex.catalogue;

/**
 * Fixed category list. Uploaders tag a post with exactly one of these.
 */
public enum Category {
	ALL("All"),
	FARMS("Farms"),
	CONTRAPTIONS("Contraptions"),
	REGEARS("Regears"),
	STASHES("Stashes"),
	GAMBLING_BASES("Gambling Bases"),
	HANGOUT_BASES("Hangout Bases"),
	MEGA_BUILDS("Mega Builds");

	private final String label;

	Category(String label) {
		this.label = label;
	}

	public String label() {
		return this.label;
	}

	/** Maps a server category code (e.g. "FARMS") to the enum, falling back to {@link #ALL}. */
	public static Category fromName(String name) {
		if (name != null) {
			for (Category value : values()) {
				if (value.name().equalsIgnoreCase(name)) {
					return value;
				}
			}
		}

		return ALL;
	}

	/** Everything except {@link #ALL}, which is the filter-off state rather than a real tag. */
	public static Category[] tags() {
		Category[] all = values();
		Category[] tags = new Category[all.length - 1];
		System.arraycopy(all, 1, tags, 0, tags.length);
		return tags;
	}
}
