package com.fudgedy.schematicindex;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchematicIndexMod implements ClientModInitializer {
	public static final String MOD_ID = "schematicindex";
	public static final Logger LOGGER = LoggerFactory.getLogger("The Schematic Index");

	@Override
	public void onInitializeClient() {
		Settings.load();
		LOGGER.info("The Schematic Index loaded (catalogue: {})",
				Settings.hasApiBaseUrl() ? Settings.apiBaseUrl() : "not configured");
	}
}
