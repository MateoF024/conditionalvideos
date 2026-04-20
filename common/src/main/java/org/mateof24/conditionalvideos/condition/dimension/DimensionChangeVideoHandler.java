package org.mateof24.conditionalvideos.condition.dimension;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;

public final class DimensionChangeVideoHandler {
    private static final String CONDITION_ID_PREFIX = "dimensionChanged:";

    private DimensionChangeVideoHandler() {
    }

    public static void onDimensionChanged(Minecraft minecraft, ResourceLocation toDimension) {
        ConditionalVideosConfig config = ConditionalVideosConfig.load();
        String dimensionId = toDimension.toString();
        ConditionalVideosConfig.ConditionConfig dimensionConfig = config.dimensionChanged().get(dimensionId);

        ConditionVideoPlayer.play(
                minecraft,
                config,
                dimensionConfig,
                CONDITION_ID_PREFIX + dimensionId,
                "dimension changed ('" + dimensionId + "')"
        );
    }
}