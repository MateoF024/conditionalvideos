package org.mateof24.conditionalvideos.condition.kill;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class KillEntityVideoHandler {
    private static final String CONDITION_ID_PREFIX = "entityKilled:";

    private KillEntityVideoHandler() {
    }

    public static Map<String, Integer> snapshotKilledCounts(Minecraft minecraft, Iterable<String> entityIds) {
        Map<String, Integer> snapshot = new HashMap<>();
        if (minecraft.player == null) {
            return snapshot;
        }

        for (String entityId : entityIds) {
            snapshot.put(entityId, resolveKilledCount(minecraft, entityId));
        }
        return snapshot;
    }

    public static void onEntityKilled(Minecraft minecraft, String entityId) {
        ConditionalVideosConfig config = ConditionalVideosConfig.load();
        ConditionalVideosConfig.ConditionConfig killConfig = config.entityKilled().get(entityId);

        ConditionVideoPlayer.play(
                minecraft,
                config,
                killConfig,
                CONDITION_ID_PREFIX + entityId,
                "entity killed ('" + entityId + "')"
        );
    }

    public static int resolveKilledCount(Minecraft minecraft, String entityId) {
        if (minecraft.player == null || entityId == null || entityId.isBlank()) {
            return 0;
        }

        ResourceLocation key = ResourceLocation.tryParse(entityId);
        if (key == null) {
            return 0;
        }

        Optional<EntityType<?>> entityTypeOptional = BuiltInRegistries.ENTITY_TYPE.getOptional(key);
        if (entityTypeOptional.isEmpty()) {
            return 0;
        }

        Stat<EntityType<?>> stat = Stats.ENTITY_KILLED.get(entityTypeOptional.get());
        return minecraft.player.getStats().getValue(stat);
    }
}