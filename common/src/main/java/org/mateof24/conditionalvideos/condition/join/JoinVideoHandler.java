package org.mateof24.conditionalvideos.condition.join;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.*;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;


public final class JoinVideoHandler {
    private static final String CONDITION_ID = "firstJoin";

    private JoinVideoHandler() {
    }

    public static boolean onJoinedSession(Minecraft minecraft) {
        ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
        ConditionalVideosConfig.ConditionConfig firstJoin = config.firstJoin();
        if (firstJoin == null || firstJoin.resolvedPlaylist().isEmpty()) {
            DebugLog.client(DebugLog.Area.JOIN, "firstJoin has no videos configured; nothing to play on join.");
            return true;
        }

        DebugLog.client(DebugLog.Area.JOIN, "firstJoin matched: {} playlist entr(ies); requesting playback.",
                firstJoin.resolvedPlaylist().size());
        ConfigSyncNetworking.notifyFirstJoinVideoState(true);
        boolean started = ConditionVideoPlayer.play(
                minecraft,
                config,
                firstJoin,
                CONDITION_ID,
                "first join",
                () -> ConfigSyncNetworking.notifyFirstJoinVideoState(false)
        );
        if (!started) {
            DebugLog.client(DebugLog.Area.JOIN, "firstJoin did not start (see the decision above).");
            ConfigSyncNetworking.notifyFirstJoinVideoState(false);
        }
        return started;
    }
}
