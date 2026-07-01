package org.mateof24.conditionalvideos.condition.death;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;

public final class PlayerDeathVideoHandler {
    private static final String CONDITION_ID = "playerDeath";
    private static final String ENTITY_CONDITION_ID_PREFIX = "deathByEntity:";
    private static final int DEFERRED_PLAY_TICKS = 4;
    private static final long KILLER_CAPTURE_VALIDITY_MILLIS = 2000L;

    private static long lastHandledAtMillis = Long.MIN_VALUE;
    private static boolean pendingDeath;
    private static int pendingDeathTicks;
    private static String capturedKillerId = "";
    private static long capturedKillerMillis = Long.MIN_VALUE;

    private PlayerDeathVideoHandler() {
    }

    // Resolve and remember the killer at the exact moment of death (combat-kill packet), before the
    // player can respawn. Playback is deferred a few ticks, and with doImmediateRespawn the player is
    // already replaced by then with its last-damage data cleared, so resolving inside executeDeathVideo
    // would lose the killer and fall back to a generic death. The captured value is the fallback.
    public static void captureKiller(Minecraft minecraft) {
        String id = resolveKillerEntityId(minecraft);
        if (!id.isBlank()) {
            capturedKillerId = id;
            capturedKillerMillis = Util.getMillis();
        }
    }

    public static void onPlayerDied(Minecraft minecraft) {
        long now = Util.getMillis();
        if (lastHandledAtMillis >= 0L && now - lastHandledAtMillis < 250L) {
            return;
        }
        lastHandledAtMillis = now;
        pendingDeath = true;
        pendingDeathTicks = 0;
    }

    public static void tickPendingDeath(Minecraft minecraft) {
        if (!pendingDeath) {
            return;
        }
        pendingDeathTicks++;
        if (pendingDeathTicks < DEFERRED_PLAY_TICKS) {
            return;
        }
        pendingDeath = false;
        executeDeathVideo(minecraft);
    }

    public static void reset() {
        pendingDeath = false;
        pendingDeathTicks = 0;
        clearCapturedKiller();
    }

    private static void executeDeathVideo(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }

        ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
        String killerEntityId = resolveKillerWithCapture(minecraft);
        clearCapturedKiller();
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

    private static String resolveKillerWithCapture(Minecraft minecraft) {
        String live = resolveKillerEntityId(minecraft);
        if (!live.isBlank()) {
            return live;
        }
        if (!capturedKillerId.isBlank() && Util.getMillis() - capturedKillerMillis <= KILLER_CAPTURE_VALIDITY_MILLIS) {
            return capturedKillerId;
        }
        return "";
    }

    private static void clearCapturedKiller() {
        capturedKillerId = "";
        capturedKillerMillis = Long.MIN_VALUE;
    }

    private static String resolveKillerEntityId(Minecraft minecraft) {
        if (minecraft.player == null) {
            return "";
        }
        DamageSource lastDamageSource = minecraft.player.getLastDamageSource();
        if (lastDamageSource != null) {
            Entity attacker = lastDamageSource.getEntity();
            if (attacker != null) {
                Identifier attackerId = BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType());
                if (attackerId != null) {
                    return attackerId.toString();
                }
            }

            Entity directAttacker = lastDamageSource.getDirectEntity();
            if (directAttacker != null) {
                Identifier directAttackerId = BuiltInRegistries.ENTITY_TYPE.getKey(directAttacker.getType());
                if (directAttackerId != null) {
                    return directAttackerId.toString();
                }
            }
        }

        LivingEntity killer = minecraft.player.getLastHurtByMob();
        if (killer == null) {
            return "";
        }
        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType());
        return key == null ? "" : key.toString();
    }
}
