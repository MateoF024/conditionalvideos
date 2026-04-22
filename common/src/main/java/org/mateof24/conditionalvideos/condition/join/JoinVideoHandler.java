package org.mateof24.conditionalvideos.condition.join;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.*;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;


public final class JoinVideoHandler {
    private static final String CONDITION_ID = "firstJoin";

    private JoinVideoHandler() {
    }

    public static boolean onJoinedSession(Minecraft minecraft) {
        ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
        ConditionalVideosConfig.ConditionConfig firstJoin = config.firstJoin();
        if (firstJoin == null || firstJoin.video().isBlank()) {
            return true;
        }

        ConfigSyncNetworking.notifyFirstJoinVideoState(true);

        boolean started = ConditionVideoPlayer.play(
                minecraft,
                config,
                firstJoin,
                CONDITION_ID,
                "first join",
                () -> {
                    ConfigSyncNetworking.notifyFirstJoinVideoState(false);
                }
        );

        if (!started) {
            ConfigSyncNetworking.notifyFirstJoinVideoState(false);
        }
        return started;
    }
}
