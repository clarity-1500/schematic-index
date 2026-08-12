package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.catalogue.Backend;
import org.jetbrains.annotations.Nullable;

/**
 * Uploader access. A code is validated against the server ({@code GET /uploader}); on success the
 * display name it belongs to is kept for the session, and the code itself is held so uploads can send
 * it as the {@code X-Upload-Code} header. Revoking a code server-side takes effect on the next check.
 */
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

	/** The redeemed code, for the upload header. */
	public static @Nullable String code() {
		return code;
	}

	/**
	 * Validates a code against the server and, if good, unlocks uploading. Blocking - call off the
	 * render thread.
	 *
	 * @return the display name the code belongs to, or null if it is not valid
	 */
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
