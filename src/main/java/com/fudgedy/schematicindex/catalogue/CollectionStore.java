package com.fudgedy.schematicindex.catalogue;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Named, user-created collections of saved posts. Stored locally (like Bookmarks) as a
// JSON map of collection name -> ordered post ids.
public final class CollectionStore {
	private static final String FILE_NAME = SchematicIndexMod.MOD_ID + "-collections.json";
	private static final int MAX_NAME = 40;

	private static final Map<String, LinkedHashSet<String>> DATA = new LinkedHashMap<>();
	private static boolean loaded;

	private CollectionStore() {
	}

	public static synchronized List<String> names() {
		ensureLoaded();
		return new ArrayList<>(DATA.keySet());
	}

	public static synchronized boolean isEmpty() {
		ensureLoaded();
		return DATA.isEmpty();
	}

	public static synchronized boolean create(String name) {
		ensureLoaded();
		String trimmed = normalize(name);

		if (trimmed.isEmpty() || DATA.containsKey(trimmed)) {
			return false;
		}

		DATA.put(trimmed, new LinkedHashSet<>());
		save();
		return true;
	}

	public static synchronized void delete(String name) {
		ensureLoaded();

		if (DATA.remove(name) != null) {
			save();
		}
	}

	public static synchronized boolean rename(String from, String to) {
		ensureLoaded();
		String cleaned = normalize(to);

		if (cleaned.isEmpty() || !DATA.containsKey(from) || (DATA.containsKey(cleaned) && !cleaned.equals(from))) {
			return false;
		}

		Map<String, LinkedHashSet<String>> rebuilt = new LinkedHashMap<>();

		for (Map.Entry<String, LinkedHashSet<String>> entry : DATA.entrySet()) {
			rebuilt.put(entry.getKey().equals(from) ? cleaned : entry.getKey(), entry.getValue());
		}

		DATA.clear();
		DATA.putAll(rebuilt);
		save();
		return true;
	}

	public static synchronized Set<String> postIds(String name) {
		ensureLoaded();
		LinkedHashSet<String> set = DATA.get(name);
		return set == null ? Set.of() : new LinkedHashSet<>(set);
	}

	public static synchronized int size(String name) {
		ensureLoaded();
		LinkedHashSet<String> set = DATA.get(name);
		return set == null ? 0 : set.size();
	}

	public static synchronized boolean contains(String name, String id) {
		ensureLoaded();
		LinkedHashSet<String> set = DATA.get(name);
		return set != null && set.contains(id);
	}

	public static synchronized boolean toggle(String name, String id) {
		ensureLoaded();
		LinkedHashSet<String> set = DATA.get(name);

		if (set == null) {
			return false;
		}

		boolean nowIn = !set.remove(id);

		if (nowIn) {
			set.add(id);
		}

		save();
		return nowIn;
	}

	public static String normalize(String name) {
		if (name == null) {
			return "";
		}

		String trimmed = name.trim();
		return trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME) : trimmed;
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}

		loaded = true;
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

		if (!Files.exists(path)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			JsonElement root = JsonParser.parseReader(reader);

			if (root.isJsonObject()) {
				for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
					LinkedHashSet<String> set = new LinkedHashSet<>();

					if (entry.getValue().isJsonArray()) {
						for (JsonElement id : entry.getValue().getAsJsonArray()) {
							set.add(id.getAsString());
						}
					}

					DATA.put(entry.getKey(), set);
				}
			}
		} catch (Exception e) {
			SchematicIndexMod.LOGGER.warn("Could not read collections", e);
		}
	}

	private static void save() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		JsonObject root = new JsonObject();

		for (Map.Entry<String, LinkedHashSet<String>> entry : DATA.entrySet()) {
			JsonArray ids = new JsonArray();

			for (String id : entry.getValue()) {
				ids.add(id);
			}

			root.add(entry.getKey(), ids);
		}

		try {
			Files.createDirectories(path.getParent());

			try (Writer writer = Files.newBufferedWriter(path)) {
				writer.write(root.toString());
			}
		} catch (IOException e) {
			SchematicIndexMod.LOGGER.warn("Could not write collections", e);
		}
	}
}
