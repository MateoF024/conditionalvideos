package org.mateof24.conditionalvideos.condition.kill;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class KillEntityVideoHandler {
    private static final String CONDITION_ID_PREFIX = "entityKilled:";
    private static final Map<String, Integer> knownKilledCounts = new HashMap<>();
    private static boolean sessionActive;

    private KillEntityVideoHandler() {
    }

    public static void onSessionStarted(Minecraft minecraft) {
        Set<String> trackedEntities = ConditionalVideosConfig.load().entityKilled().keySet();
        knownKilledCounts.clear();
        knownKilledCounts.putAll(snapshotKilledCounts(minecraft, trackedEntities));
        sessionActive = true;
    }

    public static void onSessionEnded() {
        sessionActive = false;
        knownKilledCounts.clear();
    }

    public static void onAwardStatsPacketApplied(Minecraft minecraft, Object packet) {
        if (!sessionActive || packet == null) {
            return;
        }

        ConditionalVideosConfig config = ConditionalVideosConfig.load();
        Map<String, ConditionalVideosConfig.ConditionConfig> configuredKills = config.entityKilled();
        if (configuredKills.isEmpty()) {
            return;
        }

        Object statsMapObject = resolveStatsMap(packet);
        if (!(statsMapObject instanceof Iterable<?> iterable)) {
            return;
        }

        for (Object entryObject : iterable) {
            String entityId = resolveKilledEntityId(entryObject);
            if (entityId.isBlank() || !configuredKills.containsKey(entityId)) {
                continue;
            }

            Integer updatedCount = resolveUpdatedCount(entryObject);
            if (updatedCount == null) {
                continue;
            }

            int previous = knownKilledCounts.getOrDefault(entityId, 0);
            knownKilledCounts.put(entityId, updatedCount);
            if (updatedCount > previous) {
                onEntityKilled(minecraft, entityId);
                break;
            }
        }
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

    private static Object resolveStatsMap(Object packet) {
        try {
            Method method = packet.getClass().getMethod("getStats");
            Object statsMap = method.invoke(packet);
            try {
                Method object2IntEntrySet = statsMap.getClass().getMethod("object2IntEntrySet");
                return object2IntEntrySet.invoke(statsMap);
            } catch (ReflectiveOperationException ignored) {
                try {
                    Method entrySet = statsMap.getClass().getMethod("entrySet");
                    return entrySet.invoke(statsMap);
                } catch (ReflectiveOperationException ignoredAgain) {
                    return null;
                }
            }
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static String resolveKilledEntityId(Object entryObject) {
        if (entryObject == null) {
            return "";
        }

        Object statObject = invokeNoArgs(entryObject, "getKey");
        if (statObject == null) {
            return "";
        }

        Object statValue = invokeNoArgs(statObject, "getValue");
        if (!(statValue instanceof EntityType<?> entityType)) {
            return "";
        }

        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        return key == null ? "" : key.toString();
    }

    private static Integer resolveUpdatedCount(Object entryObject) {
        Object countObject = invokeNoArgs(entryObject, "getIntValue");
        if (countObject instanceof Integer value) {
            return value;
        }

        countObject = invokeNoArgs(entryObject, "getValue");
        if (countObject instanceof Integer value) {
            return value;
        }
        return null;
    }

    private static Object invokeNoArgs(Object target, String methodName) {
        if (target == null) {
            return null;
        }

        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}