package org.mateof24.conditionalvideos.condition.advancement;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AdvancementVideoHandler {
    private static final String CONDITION_ID_PREFIX = "advancementCompleted:";

    private AdvancementVideoHandler() {
    }

    public static Set<String> snapshotCompletedAdvancements(Minecraft minecraft) {
        Set<String> completed = new HashSet<>();
        Object advancements = resolveClientAdvancements(minecraft);
        if (advancements == null) {
            return completed;
        }

        Map<?, ?> progressMap = resolveProgressMap(advancements);
        if (progressMap == null) {
            return completed;
        }

        for (Map.Entry<?, ?> entry : progressMap.entrySet()) {
            Object advancement = entry.getKey();
            Object progress = entry.getValue();
            if (!isCompleted(progress)) {
                continue;
            }

            String advancementId = resolveAdvancementId(advancement);
            if (!advancementId.isBlank()) {
                completed.add(advancementId);
            }
        }
        return completed;
    }

    public static void onAdvancementCompleted(Minecraft minecraft, String advancementId) {
        ConditionalVideosConfig config = ConditionalVideosConfig.load();
        ConditionalVideosConfig.ConditionConfig advancementConfig = config.advancementCompleted().get(advancementId);

        ConditionVideoPlayer.play(
                minecraft,
                config,
                advancementConfig,
                CONDITION_ID_PREFIX + advancementId,
                "advancement completed ('" + advancementId + "')"
        );
    }

    private static Object resolveClientAdvancements(Minecraft minecraft) {
        if (minecraft.getConnection() == null) {
            return null;
        }

        try {
            Method getAdvancements = minecraft.getConnection().getClass().getMethod("getAdvancements");
            return getAdvancements.invoke(minecraft.getConnection());
        } catch (ReflectiveOperationException exception) {
            ConditionalVideos.LOGGER.debug("Unable to resolve client advancements for conditional videos.", exception);
            return null;
        }
    }

    private static Map<?, ?> resolveProgressMap(Object advancements) {
        for (Field field : advancements.getClass().getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(advancements);
                if (value instanceof Map<?, ?> map) {
                    return map;
                }
            } catch (ReflectiveOperationException ignored) {
                // Continue with the next field.
            }
        }
        return null;
    }

    private static boolean isCompleted(Object progress) {
        if (progress == null) {
            return false;
        }

        try {
            Method isDone = progress.getClass().getMethod("isDone");
            Object done = isDone.invoke(progress);
            return done instanceof Boolean completed && completed;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private static String resolveAdvancementId(Object advancement) {
        if (advancement == null) {
            return "";
        }

        try {
            Method getId = advancement.getClass().getMethod("getId");
            Object id = getId.invoke(advancement);
            return id == null ? "" : id.toString();
        } catch (ReflectiveOperationException exception) {
            return "";
        }
    }
}