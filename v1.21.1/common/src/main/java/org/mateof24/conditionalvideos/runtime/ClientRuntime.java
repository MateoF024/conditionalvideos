package org.mateof24.conditionalvideos.runtime;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.condition.join.JoinVideoHandler;
import org.mateof24.conditionalvideos.condition.death.PlayerDeathVideoHandler;
import net.minecraft.resources.ResourceLocation;
import org.mateof24.conditionalvideos.condition.advancement.AdvancementVideoHandler;
import org.mateof24.conditionalvideos.condition.dimension.DimensionChangeVideoHandler;
import org.mateof24.conditionalvideos.condition.kill.KillEntityVideoHandler;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;

// Per-tick client dispatcher: detects session start/end, the firstJoin flow, death, and dimension
// change, handing each off to its condition handler.
public final class ClientRuntime {
    private static final int DIMENSION_VIDEO_DELAY_TICKS = 20;
    private static final int HANDSHAKE_TIMEOUT_TICKS = 100;
    private static final int JOIN_VIDEO_RETRY_TIMEOUT_TICKS = 200;

    private boolean wasInSession;
    private boolean wasAlive;
    private ResourceLocation lastDimension;
    private ResourceLocation pendingDimensionVideoTarget;
    private int pendingDimensionVideoTicks;
    private boolean joinVideoHandled;
    private int joinWaitTicks;

    public void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            if (wasInSession) {
                DebugLog.client(DebugLog.Area.LIFECYCLE, "Left the world; resetting the session state.");
                KillEntityVideoHandler.onSessionEnded();
                ConfigSyncNetworking.onClientDisconnected();
                PlaybackControlNetworking.resetClientSession();
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
            joinWaitTicks = 0;
            return;
        }

        if (!wasInSession) {
            wasInSession = true;
            DebugLog.setClientSituation(minecraft.hasSingleplayerServer() ? DebugLog.CLIENT_LOCAL : DebugLog.CLIENT_REMOTE);
            DebugLog.client(DebugLog.Area.LIFECYCLE, "Joined a world ({}, dimension {}).",
                    minecraft.getCurrentServer() == null ? "singleplayer" : "server '" + minecraft.getCurrentServer().ip + "'", minecraft.level.dimension().location());
            KillEntityVideoHandler.onSessionStarted();
            PlayerDeathVideoHandler.reset();
            AdvancementVideoHandler.reset();
            lastDimension = minecraft.level.dimension().location();
            ConditionVideoPlayer.armFirstJoinGuard();
            joinVideoHandled = false;
            joinWaitTicks = 0;
            if (ActiveConfigResolver.isMultiplayerSession(minecraft)) {
                ConfigSyncNetworking.requestServerSync(false);
            }
        }

        PlaybackControlNetworking.tickClientSession(ConditionVideoPlayer.isVideoActive(minecraft));
        ConditionVideoPlayer.tickDelays(minecraft);
        ConditionVideoPlayer.tickPreparation(minecraft);

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

        ResourceLocation currentDimension = minecraft.level.dimension().location();
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
            if (!ActiveConfigResolver.watchedFromServer() && ++joinWaitTicks < HANDSHAKE_TIMEOUT_TICKS) {
                return;
            }
            if (!ActiveConfigResolver.watchedFromServer()) {
                DebugLog.client(DebugLog.Area.JOIN, "The integrated server sent no watched list after {} ticks; watched conditions are only kept for this session.",
                        HANDSHAKE_TIMEOUT_TICKS);
            }
            JoinVideoHandler.onJoinedSession(minecraft);
            joinVideoHandled = true;
            return;
        }

        ActiveConfigResolver.RemoteConfigState state = ActiveConfigResolver.remoteConfigState();
        if (state == ActiveConfigResolver.RemoteConfigState.AVAILABLE) {
            if (JoinVideoHandler.onJoinedSession(minecraft)) {
                joinVideoHandled = true;
            } else {
                joinWaitTicks++;
                if (joinWaitTicks >= JOIN_VIDEO_RETRY_TIMEOUT_TICKS) {
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

        joinWaitTicks++;
        if (joinWaitTicks % 40 == 0) {
            ConfigSyncNetworking.requestServerSync(true);
        }
        if (joinWaitTicks >= HANDSHAKE_TIMEOUT_TICKS) {
            DebugLog.client(DebugLog.Area.NETWORK, "No ConditionalVideos data from the server after {} ticks; treating it as a server without the mod.",
                    HANDSHAKE_TIMEOUT_TICKS);
            ActiveConfigResolver.markRemoteUnavailable();
            joinVideoHandled = true;
        }
    }

    private void tryPlayPendingDimensionVideo(Minecraft minecraft, ResourceLocation currentDimension) {
        if (pendingDimensionVideoTarget == null || !pendingDimensionVideoTarget.equals(currentDimension)) {
            return;
        }

        pendingDimensionVideoTicks++;
        if (pendingDimensionVideoTicks < DIMENSION_VIDEO_DELAY_TICKS) {
            return;
        }

        if (minecraft.screen != null) {
            return;
        }

        DimensionChangeVideoHandler.onDimensionChanged(minecraft, currentDimension);
        pendingDimensionVideoTarget = null;
        pendingDimensionVideoTicks = 0;
    }
}
