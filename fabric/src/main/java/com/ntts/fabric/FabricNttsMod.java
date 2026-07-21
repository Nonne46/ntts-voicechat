package com.ntts.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FabricNttsMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ntts");

    @Override
    public void onInitialize() {
        LOGGER.info("Loaded /N/TTS for Fabric");
    }
}
