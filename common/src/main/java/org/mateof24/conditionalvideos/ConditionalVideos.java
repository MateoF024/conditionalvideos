package org.mateof24.conditionalvideos;

import org.mateof24.conditionalvideos.runtime.ClientRuntime;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConditionalVideos {
    public static final String MOD_ID = "conditionalvideos";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final ClientRuntime CLIENT_RUNTIME = new ClientRuntime();

    private ConditionalVideos() {
    }

    public static void init() {
        if (Platform.getEnvironment() == Env.CLIENT) {
            ConditionalVideosConfig.load(ConditionalVideosConfig.clientConfigPath(Platform.getGameFolder()));
        } else {
            ConditionalVideosConfig.load(ConditionalVideosConfig.serverConfigPath(Platform.getGameFolder()));
        }
        ConfigSyncNetworking.init();
        LOGGER.info("Initializing ConditionalVideos core.");
    }

    public static void onClientTick() {
        CLIENT_RUNTIME.onClientTick();
    }
}