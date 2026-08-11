package com.fudgedy.schematicindex.catalogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stand-in for the remote index while there is no backend. Deterministic, so the layout looks the
 * same every launch. Replace {@link #entries()} with the parsed index JSON later - nothing in the
 * GUI reads anything else.
 */
public final class MockCatalogue {
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

	private static final String[] POSTERS = {
			"Fudgedy", "SableCo", "hexbuild", "Orchid", "Vermillion", "AshenVaults", "reallylongusername99"
	};

	private static final String[] DESIGNERS = {
			"Nyx", "Kai", "ilmango", "Rays Works", "unknown", "Chapman", "TheySaidItCouldntBeDone"
	};

	private static final String[][] NAMES = {
			// Farms
			{"Iron Farm Mk3", "Compact Gold Farm", "Villager Breeder", "Kelp Farm Tower", "Raid Farm Shell", "Bamboo Column"},
			// Contraptions
			{"Hidden Piston Door", "3x3 Vault Door", "Flying Machine Bore", "Auto Smelter Bank", "Item Elevator", "Flush Trapdoor Array"},
			// Regears
			{"Full Regear Room", "Quick Kit Station", "Crystal Prep Bench", "Pot Regear Corner", "Loadout Wall", "Gear Swap Loop"},
			// Stashes
			{"Obsidian Stash Shell", "Buried Bunker", "Deepslate Vault", "Nether Roof Stash", "Ocean Floor Cache", "Decoy Stash Ring"},
			// Gambling Bases
			{"Coinflip Hall", "Dice Den", "Wheel Room", "High Roller Lounge", "Slots Arcade", "Jackpot Tower"},
			// Hangout Bases
			{"Cherry Pavilion", "Rooftop Terrace", "Koi Courtyard", "Lantern Bridge Camp", "Glass Atrium", "Campfire Commons"},
			// Mega Builds
			{"Skyline Citadel", "Floating Archipelago", "Cathedral of Ash", "Colossus Gateway", "Storm Spire", "The Long Wall"}
	};

	private static final List<SchematicEntry> ENTRIES = build();
	private static int postCounter;

	private MockCatalogue() {
	}

	public static List<SchematicEntry> entries() {
		return ENTRIES;
	}

	/**
	 * Beta only: a post made in-game goes straight into the in-memory list so it shows up in the grid
	 * immediately. The real flow uploads to the catalogue and the index is refetched.
	 */
	public static SchematicEntry post(SchematicEntry entry) {
		ENTRIES.add(0, entry);
		return entry;
	}

	public static String nextPostId() {
		return "post-" + (++postCounter);
	}

	private static List<SchematicEntry> build() {
		List<SchematicEntry> list = new ArrayList<>();
		Category[] tags = Category.tags();
		int seed = 7;

		for (int group = 0; group < tags.length && group < NAMES.length; group++) {
			Category category = tags[group];

			for (int i = 0; i < NAMES[group].length; i++) {
				// Cheap deterministic pseudo-random so the mock numbers look varied but never change.
				seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF;
				int roll = seed >> 8;

				int sizeX = 9 + (roll % 40);
				int sizeZ = 9 + ((roll >> 3) % 40);
				int sizeY = 5 + ((roll >> 6) % 30);
				int blocks = sizeX * sizeY * sizeZ / (3 + (roll >> 9) % 4);

				list.add(new SchematicEntry(
						String.format(Locale.ROOT, "%s-%02d", category.name().toLowerCase(Locale.ROOT), i),
						NAMES[group][i],
						POSTERS[(group + i) % POSTERS.length],
						DESIGNERS[(group * 3 + i) % DESIGNERS.length],
						category,
						sizeX, sizeY, sizeZ,
						blocks,
						120 + (roll % 40000),
						4 + (roll % 900),
						(1 + (roll % 27)) + "d ago",
						"Placeholder description. The real one comes from the catalogue index and can run to "
								+ "a few lines, so the detail panel needs to wrap and clamp it properly.",
						1 + (roll % 4),
						Math.abs(roll / 13),
						(roll % 5) == 0
				));
			}
		}

		// Mutable: beta posts made in-game are inserted at the front.
		return list;
	}
}
