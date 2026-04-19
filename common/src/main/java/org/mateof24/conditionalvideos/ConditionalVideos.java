package org.mateof24.conditionalvideos;

import org.mateof24.conditionalvideos.runtime.ClientRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConditionalVideos {
    public static final String MOD_ID = "conditionalvideos";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final ClientRuntime CLIENT_RUNTIME = new ClientRuntime();

    private ConditionalVideos() {
    }

    public static void init() {
        LOGGER.info("Initializing ConditionalVideos core.");
    }

    public static void onClientTick() {
        CLIENT_RUNTIME.onClientTick();
    }
}