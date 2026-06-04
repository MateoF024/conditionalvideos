package org.mateof24.conditionalvideos.condition.server;

import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ScoreboardConditionConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ServerConditionEvents {
    private static final Map<UUID, Boolean> sleepingState = new HashMap<>();
    private static final Map<UUID, Map<String, Boolean>> scoreboardArmed = new HashMap<>();

    private ServerConditionEvents() {
    }

    public static void init() {
        PlayerEvent.PICKUP_ITEM_POST.register((player, entity, stack) -> onItemObtained(player, stack));
        PlayerEvent.CRAFT_ITEM.register((player, stack, container) -> {
            onItemObtained(player, stack);
            onItemCrafted(player, stack);
        });
        PlayerEvent.SMELT_ITEM.register(ServerConditionEvents::onItemObtained);
        PlayerEvent.PLAYER_JOIN.register(ServerConditionEvents::onPlayerJoin);
        PlayerEvent.PLAYER_QUIT.register(player -> {
            ServerConditionDispatcher.clearPlayer(player);
            sleepingState.remove(player.getUUID());
            scoreboardArmed.remove(player.getUUID());
        });
        TickEvent.SERVER_POST.register(ServerConditionEvents::onServerTick);
    }

    // Seed the armed state from the CURRENT scores at join, WITHOUT firing. The scoreboard scores
    // persist with the world, so a condition that already held before quitting starts armed=true on
    // rejoin and never re-fires. It only fires again if the score drops below the comparator (re-arms)
    // and crosses it again while online. This is what gives cross-session persistence with no disk store.
    private static void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Map<String, ScoreboardConditionConfig> conditions = ServerConditionDispatcher.activeConfig(server).scoreboard();
        if (conditions.isEmpty()) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        Map<String, Boolean> armed = scoreboardArmed.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
        String holder = player.getScoreboardName();
        for (Map.Entry<String, ScoreboardConditionConfig> entry : conditions.entrySet()) {
            String objectiveName = entry.getKey();
            Objective objective = scoreboard.getObjective(objectiveName);
            boolean satisfied = objective != null
                    && scoreboard.hasPlayerScore(holder, objective)
                    && entry.getValue().matches(scoreboard.getOrCreatePlayerScore(holder, objective).getScore());
            armed.put(objectiveName, satisfied);
        }
    }

    private static void onItemObtained(Player player, ItemStack stack) {
        if (!(player instanceof ServerPlayer serverPlayer) || stack == null || stack.isEmpty()) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null) {
            ServerConditionDispatcher.fire(serverPlayer, ConditionRegistry.TYPE_ITEM_OBTAINED + "/" + id);
        }
    }

    private static void onItemCrafted(Player player, ItemStack stack) {
        if (!(player instanceof ServerPlayer serverPlayer) || stack == null || stack.isEmpty()) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null) {
            ServerConditionDispatcher.fire(serverPlayer, ConditionRegistry.TYPE_ITEM_CRAFTED + "/" + id);
        }
    }

    private static void onServerTick(MinecraftServer server) {
        ConditionalVideosConfig config = ServerConditionDispatcher.activeConfig(server);
        Map<String, ScoreboardConditionConfig> scoreboardConditions = config.scoreboard();
        Scoreboard scoreboard = server.getScoreboard();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();

            boolean sleeping = player.isSleeping();
            boolean wasSleeping = sleepingState.getOrDefault(uuid, false);
            if (sleeping && !wasSleeping) {
                ServerConditionDispatcher.fire(player, ConditionRegistry.KEY_BED_SLEEP);
            }
            sleepingState.put(uuid, sleeping);

            if (!scoreboardConditions.isEmpty()) {
                evaluateScoreboard(scoreboard, player, scoreboardConditions);
            }
        }
    }

    private static void evaluateScoreboard(Scoreboard scoreboard, ServerPlayer player,
                                           Map<String, ScoreboardConditionConfig> conditions) {
        Map<String, Boolean> armed = scoreboardArmed.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
        String holder = player.getScoreboardName();

        for (Map.Entry<String, ScoreboardConditionConfig> entry : conditions.entrySet()) {
            String objectiveName = entry.getKey();
            ScoreboardConditionConfig condition = entry.getValue();
            Objective objective = scoreboard.getObjective(objectiveName);
            if (objective == null || !scoreboard.hasPlayerScore(holder, objective)) {
                armed.put(objectiveName, false);
                continue;
            }
            int score = scoreboard.getOrCreatePlayerScore(holder, objective).getScore();
            boolean satisfied = condition.matches(score);
            boolean wasSatisfied = armed.getOrDefault(objectiveName, false);
            if (satisfied && !wasSatisfied) {
                ServerConditionDispatcher.fire(player, ConditionRegistry.TYPE_SCOREBOARD + "/" + objectiveName);
            }
            armed.put(objectiveName, satisfied);
        }
    }
}
