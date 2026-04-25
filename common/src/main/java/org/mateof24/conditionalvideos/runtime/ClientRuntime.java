package org.mateof24.conditionalvideos.runtime;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.condition.join.JoinVideoHandler;
import org.mateof24.conditionalvideos.condition.death.PlayerDeathVideoHandler;
import net.minecraft.resources.ResourceLocation;
import org.mateof24.conditionalvideos.condition.advancement.AdvancementVideoHandler;
import org.mateof24.conditionalvideos.condition.dimension.DimensionChangeVideoHandler;
import org.mateof24.conditionalvideos.condition.kill.KillEntityVideoHandler;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;

import java.util.*;

public final class ClientRuntime {
    private static final int DIMENSION_VIDEO_DELAY_TICKS = 20;
    private static final int MULTIPLAYER_HANDSHAKE_TIMEOUT_TICKS = 100;
    private static final int JOIN_VIDEO_RETRY_TIMEOUT_TICKS = 200;

    private boolean wasInSession;
    private boolean wasAlive;
    private ResourceLocation lastDimension;
    private ResourceLocation pendingDimensionVideoTarget;
    private int pendingDimensionVideoTicks;
    private Set<String> completedAdvancements = new LinkedHashSet<>();
    private boolean joinVideoHandled;
    private int multiplayerHandshakeTicks;

    public void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            if (wasInSession) {
                KillEntityVideoHandler.onSessionEnded();
                ConfigSyncNetworking.onClientDisconnected();
            }
            wasInSession = false;
            wasAlive = false;
            lastDimension = null;
            pendingDimensionVideoTarget = null;
            pendingDimensionVideoTicks = 0;
            completedAdvancements = new LinkedHashSet<>();
            joinVideoHandled = false;
            multiplayerHandshakeTicks = 0;
            return;
        }

        if (!wasInSession) {
            wasInSession = true;
            KillEntityVideoHandler.onSessionStarted();
            lastDimension = minecraft.level.dimension().location();
            completedAdvancements = AdvancementVideoHandler.snapshotCompletedAdvancements(minecraft);
            joinVideoHandled = false;
            multiplayerHandshakeTicks = 0;
            if (ActiveConfigResolver.isMultiplayerSession(minecraft)) {
                ConfigSyncNetworking.requestServerSync(false);
            }
        }

        if (!joinVideoHandled) {
            if (!ActiveConfigResolver.isMultiplayerSession(minecraft)) {
                JoinVideoHandler.onJoinedSession(minecraft);
                joinVideoHandled = true;
            } else {
                if (ActiveConfigResolver.remoteConfigState() == ActiveConfigResolver.RemoteConfigState.AVAILABLE) {
                    if (JoinVideoHandler.onJoinedSession(minecraft)) {
                        joinVideoHandled = true;
                    } else {
                        multiplayerHandshakeTicks++;
                        if (multiplayerHandshakeTicks >= JOIN_VIDEO_RETRY_TIMEOUT_TICKS) {
                            joinVideoHandled = true;
                        }
                    }
                } else if (ActiveConfigResolver.remoteConfigState() == ActiveConfigResolver.RemoteConfigState.UNAVAILABLE) {
                    joinVideoHandled = true;
                } else {
                    multiplayerHandshakeTicks++;
                    if (multiplayerHandshakeTicks % 40 == 0) {
                        ConfigSyncNetworking.requestServerSync(true);
                    }
                    if (multiplayerHandshakeTicks >= MULTIPLAYER_HANDSHAKE_TIMEOUT_TICKS) {
                        ActiveConfigResolver.markRemoteUnavailable();
                        joinVideoHandled = true;
                    }
                }
            }
        }

        boolean isAlive = minecraft.player.isAlive();
        if (wasAlive && !isAlive) {
            PlayerDeathVideoHandler.onPlayerDied(minecraft);
        }
        wasAlive = isAlive;

        ResourceLocation currentDimension = minecraft.level.dimension().location();
        if (lastDimension != null && !lastDimension.equals(currentDimension)) {
            pendingDimensionVideoTarget = currentDimension;
            pendingDimensionVideoTicks = 0;
        }
        lastDimension = currentDimension;
        tryPlayPendingDimensionVideo(minecraft, currentDimension);

        Set<String> nowCompleted = AdvancementVideoHandler.snapshotCompletedAdvancements(minecraft);
        for (String advancementId : nowCompleted) {
            if (!completedAdvancements.contains(advancementId)) {
                if (AdvancementVideoHandler.onAdvancementCompleted(minecraft, advancementId)) {
                    break;
                }
            }
        }
        completedAdvancements = nowCompleted;

        KillEntityVideoHandler.onClientTick(minecraft);
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