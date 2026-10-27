package org.mateof24.conditionalvideos.runtime;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.condition.join.JoinVideoHandler;
import org.mateof24.conditionalvideos.condition.death.PlayerDeathVideoHandler;
import net.minecraft.resources.Identifier;
import org.mateof24.conditionalvideos.condition.advancement.AdvancementVideoHandler;
import org.mateof24.conditionalvideos.condition.dimension.DimensionChangeVideoHandler;
import org.mateof24.conditionalvideos.condition.join.SessionKeyResolver;
import org.mateof24.conditionalvideos.condition.kill.KillEntityVideoHandler;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;

// Per-tick client dispatcher: detects session start/end, the firstJoin flow, death, and dimension
// change, handing each off to its condition handler.
public final class ClientRuntime {
    private static final int DIMENSION_VIDEO_DELAY_TICKS = 20;
    private static final int MULTIPLAYER_HANDSHAKE_TIMEOUT_TICKS = 100;
    private static final int JOIN_VIDEO_RETRY_TIMEOUT_TICKS = 200;

    private boolean wasInSession;
    private boolean wasAlive;
    private Identifier lastDimension;
    private Identifier pendingDimensionVideoTarget;
    private int pendingDimensionVideoTicks;
    private boolean joinVideoHandled;
    private int multiplayerHandshakeTicks;

    public void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            if (wasInSession) {
                DebugLog.client(DebugLog.Area.LIFECYCLE, "Left the world; resetting the session state.");
                KillEntityVideoHandler.onSessionEnded();
                ConfigSyncNetworking.onClientDisconnected();
                AdvancementVideoHandler.reset();
                ActiveConfigResolver.invalidateLocalConfigCache();
                ConditionVideoPlayer.clearQueue();
                ConditionVideoPlayer.armFirstJoinGuard();
                DebugLog.setClientSituation(DebugLog.CLIENT_MENU);
            }
            wasInSession = false;
            wasAlive = false;
            lastDimension = null;
            pendingDimensionVideoTarget = null;
            pendingDimensionVideoTicks = 0;
            joinVideoHandled = false;
            multiplayerHandshakeTicks = 0;
            return;
        }

        if (!wasInSession) {
            wasInSession = true;
            DebugLog.setClientSituation(minecraft.hasSingleplayerServer() ? DebugLog.CLIENT_LOCAL : DebugLog.CLIENT_REMOTE);
            DebugLog.client(DebugLog.Area.LIFECYCLE, "Joined a world (session '{}', dimension {}).",
                    SessionKeyResolver.resolveSessionKey(minecraft), minecraft.level.dimension().identifier());
            KillEntityVideoHandler.onSessionStarted();
            PlayerDeathVideoHandler.reset();
            AdvancementVideoHandler.reset();
            lastDimension = minecraft.level.dimension().identifier();
            ConditionVideoPlayer.armFirstJoinGuard();
            joinVideoHandled = false;
            multiplayerHandshakeTicks = 0;
            if (ActiveConfigResolver.isMultiplayerSession(minecraft)) {
                ConfigSyncNetworking.requestServerSync(false);
            }
        }

        if (!joinVideoHandled) {
            tickJoinFlow(minecraft);
            if (joinVideoHandled) {
                ConditionVideoPlayer.releaseFirstJoinGuard(minecraft);
            }
        }

        boolean isAlive = minecraft.player.isAlive();
        if (wasAlive && !isAlive) {
            PlayerDeathVideoHandler.onPlayerDied(minecraft);
        }
        wasAlive = isAlive;

        PlayerDeathVideoHandler.tickPendingDeath(minecraft);

        Identifier currentDimension = minecraft.level.dimension().identifier();
        if (lastDimension != null && !lastDimension.equals(currentDimension)) {
            DebugLog.client(DebugLog.Area.CONDITION, "Dimension changed {} -> {}; checking its video in {} ticks.",
                    lastDimension, currentDimension, DIMENSION_VIDEO_DELAY_TICKS);
            pendingDimensionVideoTarget = currentDimension;
            pendingDimensionVideoTicks = 0;
        }
        lastDimension = currentDimension;
        tryPlayPendingDimensionVideo(minecraft, currentDimension);

        AdvancementVideoHandler.tickPendingCompletions(minecraft);

        KillEntityVideoHandler.onClientTick(minecraft);
    }

    private void tickJoinFlow(Minecraft minecraft) {
        if (!ActiveConfigResolver.isMultiplayerSession(minecraft)) {
            JoinVideoHandler.onJoinedSession(minecraft);
            joinVideoHandled = true;
            return;
        }

        ActiveConfigResolver.RemoteConfigState state = ActiveConfigResolver.remoteConfigState();
        if (state == ActiveConfigResolver.RemoteConfigState.AVAILABLE) {
            if (JoinVideoHandler.onJoinedSession(minecraft)) {
                joinVideoHandled = true;
            } else {
                multiplayerHandshakeTicks++;
                if (multiplayerHandshakeTicks >= JOIN_VIDEO_RETRY_TIMEOUT_TICKS) {
                    DebugLog.client(DebugLog.Area.JOIN, "firstJoin could not start after {} ticks; giving up for this session.",
                            JOIN_VIDEO_RETRY_TIMEOUT_TICKS);
                    joinVideoHandled = true;
                }
            }
            return;
        }

        if (state == ActiveConfigResolver.RemoteConfigState.UNAVAILABLE) {
            DebugLog.client(DebugLog.Area.JOIN, "The server has no ConditionalVideos data; firstJoin is skipped.");
            joinVideoHandled = true;
            return;
        }

        multiplayerHandshakeTicks++;
        if (multiplayerHandshakeTicks % 40 == 0) {
            ConfigSyncNetworking.requestServerSync(true);
        }
        if (multiplayerHandshakeTicks >= MULTIPLAYER_HANDSHAKE_TIMEOUT_TICKS) {
            DebugLog.client(DebugLog.Area.NETWORK, "No ConditionalVideos data from the server after {} ticks; treating it as a server without the mod.",
                    MULTIPLAYER_HANDSHAKE_TIMEOUT_TICKS);
            ActiveConfigResolver.markRemoteUnavailable();
            joinVideoHandled = true;
        }
    }

    private void tryPlayPendingDimensionVideo(Minecraft minecraft, Identifier currentDimension) {
        if (pendingDimensionVideoTarget == null || !pendingDimensionVideoTarget.equals(currentDimension)) {
            return;
        }

        pendingDimensionVideoTicks++;
        if (pendingDimensionVideoTicks < DIMENSION_VIDEO_DELAY_TICKS) {
            return;
        }

        if (minecraft.gui.screen() != null) {
            return;
        }

        DimensionChangeVideoHandler.onDimensionChanged(minecraft, currentDimension);
        pendingDimensionVideoTarget = null;
        pendingDimensionVideoTicks = 0;
    }
}
