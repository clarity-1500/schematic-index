package com.fudgedy.schematicindex.gui;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * Beta stand-in for uploader access.
 *
 * <p>The real flow is: the owner generates a code, assigns it to a profile, and hands it out. The
 * uploader redeems it once and the server issues a session. Codes are stored hashed, checked
 * server-side, rate limited, and revocable - none of which can happen client-side.
 *
 * <p>So this class is deliberately a stub: the codes below live in the jar and anyone can read them.
 * That is fine for a local beta and must not survive contact with a backend.
 */
public final class UploaderAccess {
	private static final Map<String, String> BETA_CODES = Map.of(
			"INDEX-BETA-0001", "Fudgedy",
			"INDEX-BETA-0002", "SableCo",
			"INDEX-BETA-0003", "hexbuild"
	);

	private static @Nullable String profile;

	private UploaderAccess() {
	}

	public static boolean unlocked() {
		return profile != null;
	}

	public static @Nullable String profile() {
		return profile;
	}

	/** @return the profile the code belongs to, or null if the code is not valid */
	public static @Nullable String redeem(String code) {
		String cleaned = code.trim().toUpperCase(Locale.ROOT);
		String owner = BETA_CODES.get(cleaned);

		if (owner != null) {
			profile = owner;
		}

		return owner;
	}

	public static void signOut() {
		profile = null;
	}

	public static String betaHint() {
		return "Beta codes: INDEX-BETA-0001 / 0002 / 0003";
	}
}
