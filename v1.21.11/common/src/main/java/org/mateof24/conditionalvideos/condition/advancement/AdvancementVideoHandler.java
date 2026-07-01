package org.mateof24.conditionalvideos.condition.advancement;

import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AdvancementVideoHandler {
    private static final String CONDITION_ID_PREFIX = "advancementCompleted:";
    private static final Set<String> trackedCompleted = new HashSet<>();
    private static final Set<String> pendingNewCompletions = new HashSet<>();

    private AdvancementVideoHandler() {
    }

    public static void onPacketReceived(boolean shouldReset,
                                        Set<Identifier> removed,
                                        Map<Identifier, AdvancementProgress> progressUpdates) {
        if (shouldReset) {
            trackedCompleted.clear();
            pendingNewCompletions.clear();
        }

        for (Identifier id : removed) {
            trackedCompleted.remove(id.toString());
        }

        for (Map.Entry<Identifier, AdvancementProgress> entry : progressUpdates.entrySet()) {
            String id = entry.getKey().toString();
            boolean done = entry.getValue() != null && entry.getValue().isDone();

            if (done) {
                boolean wasNew = trackedCompleted.add(id);
                if (wasNew && !shouldReset) {
                    pendingNewCompletions.add(id);
                }
            } else {
                trackedCompleted.remove(id);
            }
        }
    }

    public static void tickPendingCompletions(Minecraft minecraft) {
        if (pendingNewCompletions.isEmpty()) {
            return;
        }
        Set<String> toProcess = new HashSet<>(pendingNewCompletions);
        pendingNewCompletions.clear();
        for (String advancementId : toProcess) {
            if (fireAdvancementVideo(minecraft, advancementId)) {
                break;
            }
        }
    }

    public static void reset() {
        trackedCompleted.clear();
        pendingNewCompletions.clear();
    }

    private static boolean fireAdvancementVideo(Minecraft minecraft, String advancementId) {
        ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
        ConditionalVideosConfig.ConditionConfig advancementConfig = config.advancementCompleted().get(advancementId);

        return ConditionVideoPlayer.play(
                minecraft,
                config,
                advancementConfig,
                CONDITION_ID_PREFIX + advancementId,
                "advancement completed ('" + advancementId + "')",
                null
        );
    }
}
