package org.mateof24.conditionalvideos.condition.death;

import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;



public final class PlayerDeathVideoHandler {
    private static final String CONDITION_ID = "playerDeath";
    private static long lastHandledAtMillis = Long.MIN_VALUE;
    private static final String ENTITY_CONDITION_ID_PREFIX = "deathByEntity:";

    private PlayerDeathVideoHandler() {
    }

    public static void onPlayerDied(Minecraft minecraft) {
        long now = Util.getMillis();
        if (lastHandledAtMillis >= 0L && now - lastHandledAtMillis < 250L) {
            return;
        }
        lastHandledAtMillis = now;

        ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
        String killerEntityId = resolveKillerEntityId(minecraft);
        if (!killerEntityId.isBlank()) {
            ConditionalVideosConfig.ConditionConfig entityConfig = config.deathByEntity().get(killerEntityId);
            if (ConditionVideoPlayer.play(
                    minecraft,
                    config,
                    entityConfig,
                    ENTITY_CONDITION_ID_PREFIX + killerEntityId,
                    "death by entity ('" + killerEntityId + "')",
                    null
            )) {
                return;
            }
        }

        ConditionVideoPlayer.play(minecraft, config, config.playerDeath(), CONDITION_ID, "player death", null);

    }
    private static String resolveKillerEntityId(Minecraft minecraft) {
        if (minecraft.player == null) {
            return "";
        }
        DamageSource lastDamageSource = minecraft.player.getLastDamageSource();
        if (lastDamageSource != null) {
            Entity attacker = lastDamageSource.getEntity();
            if (attacker != null) {
                ResourceLocation attackerId = BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType());
                if (attackerId != null) {
                    return attackerId.toString();
                }
            }

            Entity directAttacker = lastDamageSource.getDirectEntity();
            if (directAttacker != null) {
                ResourceLocation directAttackerId = BuiltInRegistries.ENTITY_TYPE.getKey(directAttacker.getType());
                if (directAttackerId != null) {
                    return directAttackerId.toString();
                }
            }
        }

        LivingEntity killer = minecraft.player.getLastHurtByMob();
        if (killer == null) {
            return "";
        }
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType());
        return key == null ? "" : key.toString();
    }
}