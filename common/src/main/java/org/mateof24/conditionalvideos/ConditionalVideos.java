package org.mateof24.conditionalvideos;

import org.mateof24.conditionalvideos.api.ConditionalVideosAPI;
import org.mateof24.conditionalvideos.command.ConditionalVideosCommands;
import org.mateof24.conditionalvideos.condition.server.ServerConditionEvents;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import org.mateof24.conditionalvideos.config.ConfigAnnouncer;
import org.mateof24.conditionalvideos.config.LegacyMigration;
import org.mateof24.conditionalvideos.debug.ClientDebug;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.ServerDebug;
import org.mateof24.conditionalvideos.world.PlaybackSessions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConditionalVideos {
    public static final String MOD_ID = "conditionalvideos";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private ConditionalVideos() {
    }

    public static void init() {
        boolean client = Platform.getEnvironment() == Env.CLIENT;
        if (client) {
            ClientDebug.init();
        }
        ServerDebug.init();
        LegacyMigration.run(!client);
        ConfigAnnouncer.init();
        PlaybackSessions.init();
        ConfigSyncNetworking.init();
        PlaybackControlNetworking.init();
        ConditionalVideosCommands.register();
        ServerConditionEvents.init();
        ConditionalVideosAPI.init();
        LOGGER.info("Initializing ConditionalVideos core.");
        if (client) {
            DebugLog.client(DebugLog.Area.LIFECYCLE, "Core initialized on the client (game folder '{}').", Platform.getGameFolder());
        } else {
            DebugLog.server(DebugLog.Area.LIFECYCLE, "Core initialized on the dedicated server (game folder '{}').", Platform.getGameFolder());
        }
    }
}
