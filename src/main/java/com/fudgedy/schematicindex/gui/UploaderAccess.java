package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.catalogue.Backend;
import org.jetbrains.annotations.Nullable;

public final class UploaderAccess {
	private static @Nullable String profile;
	private static @Nullable String code;

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

	public static @Nullable String redeem(String raw) {
		String cleaned = raw.trim();
		String owner = Backend.checkCode(cleaned);

		if (owner != null) {
			profile = owner;
			code = cleaned;
		}

		return owner;
	}

	public static void signOut() {
		profile = null;
		code = null;
	}

	public static String betaHint() {
		return "Ask an existing uploader for an access code.";
	}
}
