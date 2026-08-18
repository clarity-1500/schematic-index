package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.catalogue.Backend;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

public final class UploaderAccess {
	private static @Nullable String profile;
	private static @Nullable String code;
	private static @Nullable String ign;

	private UploaderAccess() {
	}

	public static boolean unlocked() {
		return profile != null;
	}

	public static @Nullable String profile() {
		return profile;
	}

	public static @Nullable String code() {
		return code;
	}

	public static @Nullable String ign() {
		return ign != null && !ign.isBlank() ? ign : profile;
	}

	public static @Nullable String redeem(String raw) {
		String cleaned = raw.trim();
		JsonObject info = Backend.uploaderInfo(cleaned);

		if (info == null || !info.has("displayName")) {
			return null;
		}

		profile = info.get("displayName").getAsString();
		code = cleaned;
		ign = info.has("ign") && !info.get("ign").isJsonNull() ? info.get("ign").getAsString() : null;
		return profile;
	}

	public static void signOut() {
		profile = null;
		code = null;
		ign = null;
	}

	public static String betaHint() {
		return "Ask an existing uploader for an access code.";
	}
}
