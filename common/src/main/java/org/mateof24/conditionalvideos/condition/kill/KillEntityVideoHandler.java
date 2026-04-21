package org.mateof24.conditionalvideos.condition.kill;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class KillEntityVideoHandler {
    private static final String CONDITION_ID_PREFIX = "entityKilled:";
    private static final int TRACK_TIMEOUT_TICKS = 100;
    private static final Map<Integer, TrackedAttack> trackedAttacks = new LinkedHashMap<>();
    private static boolean sessionActive;

    private KillEntityVideoHandler() {
    }

    public static void onSessionStarted() {
        trackedAttacks.clear();
        sessionActive = true;
    }

    public static void onSessionEnded() {
        sessionActive = false;
        trackedAttacks.clear();
    }

    public static void onPlayerAttackedEntity(int entityId, String entityTypeId) {
        if (!sessionActive) {
            return;
        }
        if (!ConditionalVideosConfig.load().entityKilled().containsKey(entityTypeId)) {
            return;
        }
        trackedAttacks.put(entityId, new TrackedAttack(entityTypeId));
    }

    public static void onClientTick(Minecraft minecraft) {
        if (!sessionActive || minecraft.level == null) {
            trackedAttacks.clear();
            return;
        }

        Iterator<Map.Entry<Integer, TrackedAttack>> it = trackedAttacks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, TrackedAttack> entry = it.next();
            TrackedAttack tracked = entry.getValue();
            tracked.ticks++;

            if (tracked.ticks > TRACK_TIMEOUT_TICKS) {
                it.remove();
                continue;
            }

            Entity entity = minecraft.level.getEntity(entry.getKey());
            if (entity == null || (entity instanceof LivingEntity living && !living.isAlive())) {
                it.remove();
                onEntityKilled(minecraft, tracked.entityTypeId);
                break;
            }
        }
    }

    private static void onEntityKilled(Minecraft minecraft, String entityId) {
        ConditionalVideosConfig config = ConditionalVideosConfig.load();
        ConditionalVideosConfig.ConditionConfig killConfig = config.entityKilled().get(entityId);
        ConditionVideoPlayer.play(minecraft, config, killConfig, CONDITION_ID_PREFIX + entityId, "entity killed ('" + entityId + "')");
    }

    private static final class TrackedAttack {
        final String entityTypeId;
        int ticks;

        TrackedAttack(String entityTypeId) {
            this.entityTypeId = entityTypeId;
            this.ticks = 0;
        }
    }
}