package com.fudgedy.schematicindex;

// Central registry of error codes. Every fallible feature gets a unique code so users
// can report bugs by quoting the code in the Discord. New failable features MUST add a
// new code here and route the failure through report(), which surfaces the shared
// "Something went wrong" modal instead of failing silently.
public final class Errors {
	public static final String NETWORK = "SI-NET01";
	public static final String CONTENT = "SI-NET02";
	public static final String DOWNLOAD = "SI-DL01";
	public static final String DOWNLOAD_WRITE = "SI-DL02";
	public static final String LOAD = "SI-LD01";
	public static final String UPLOAD = "SI-UP01";
	public static final String PARSE = "SI-PR01";
	public static final String IMAGE = "SI-IM01";
	public static final String PREVIEW = "SI-PV01";
	public static final String COLLECTION = "SI-CL01";
	public static final String PROFILE = "SI-PF01";
	public static final String FOLLOW = "SI-FL01";

	private static volatile String pending;

	private Errors() {
	}

	// Called from anywhere (including background threads) to surface a failure. The open
	// menu picks the pending code up on its next frame and opens the error modal.
	public static void report(String code) {
		if (pending == null) {
			pending = code;
			SchematicIndexMod.LOGGER.warn("Surfaced error {}", code);
		}
	}

	public static boolean hasPending() {
		return pending != null;
	}

	public static String takePending() {
		String code = pending;
		pending = null;
		return code;
	}
}
