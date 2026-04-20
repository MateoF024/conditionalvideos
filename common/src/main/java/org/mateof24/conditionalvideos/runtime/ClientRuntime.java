package org.mateof24.conditionalvideos.runtime;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.condition.join.JoinVideoHandler;
import org.mateof24.conditionalvideos.condition.death.PlayerDeathVideoHandler;
import net.minecraft.resources.ResourceLocation;
import org.mateof24.conditionalvideos.condition.advancement.AdvancementVideoHandler;
import org.mateof24.conditionalvideos.condition.dimension.DimensionChangeVideoHandler;
import org.mateof24.conditionalvideos.condition.kill.KillEntityVideoHandler;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;

import java.util.*;

public final class ClientRuntime {
    private static final int DIMENSION_VIDEO_DELAY_TICKS = 20;

    private boolean wasInSession;
    private boolean wasAlive;
    private ResourceLocation lastDimension;
    private ResourceLocation pendingDimensionVideoTarget;
    private int pendingDimensionVideoTicks;
    private Set<String> completedAdvancements = new LinkedHashSet<>();
    private Set<String> trackedKilledEntities = new LinkedHashSet<>();
    private Map<String, Integer> killedEntityCounts = new HashMap<>();

    public void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            if (wasInSession) {
                AdvancementVideoHandler.onSessionEnded();
                KillEntityVideoHandler.onSessionEnded();
            }
            wasInSession = false;
            wasAlive = false;
            lastDimension = null;
            pendingDimensionVideoTarget = null;
            pendingDimensionVideoTicks = 0;
            completedAdvancements = new LinkedHashSet<>();
            trackedKilledEntities = new LinkedHashSet<>();
            killedEntityCounts = new HashMap<>();
            return;
        }

        if (!wasInSession) {
            wasInSession = true;
            JoinVideoHandler.onJoinedSession(minecraft);
            AdvancementVideoHandler.onSessionStarted(minecraft);
            KillEntityVideoHandler.onSessionStarted(minecraft);
            lastDimension = minecraft.level.dimension().location();
            completedAdvancements = AdvancementVideoHandler.snapshotCompletedAdvancements(minecraft);
            refreshTrackedKilledEntities(minecraft);
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

        refreshTrackedKilledEntities(minecraft);
        Map<String, Integer> nowKilledCounts = KillEntityVideoHandler.snapshotKilledCounts(minecraft, trackedKilledEntities);
        for (Map.Entry<String, Integer> entry : nowKilledCounts.entrySet()) {
            int previous = killedEntityCounts.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > previous) {
                KillEntityVideoHandler.onEntityKilled(minecraft, entry.getKey());
                break;
            }
        }
        killedEntityCounts = nowKilledCounts;
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

    private void refreshTrackedKilledEntities(Minecraft minecraft) {
        Set<String> configuredEntities = new LinkedHashSet<>(ConditionalVideosConfig.load().entityKilled().keySet());
        if (configuredEntities.equals(trackedKilledEntities)) {
            return;
        }

        trackedKilledEntities = configuredEntities;
        killedEntityCounts = KillEntityVideoHandler.snapshotKilledCounts(minecraft, trackedKilledEntities);
    }

}