package org.mateof24.conditionalvideos.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.TeamArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.api.PlayOptions;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionFiles;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.ServerDebug;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.world.WatchedConditions;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ConditionalVideosCommands {
    private static final String TARGETS = "targets";
    private static final String DELAY_SECONDS = "seconds";
    private static final String EXCLUDED_PLAYERS = "players";
    private static final String EXCLUDED_TEAM = "team";

    // What a play command may change for that run, always written in this order after the condition.
    private enum PlayOption {
        DELAY("delay"), EXCLUDE("exclude"), EXCLUDE_TEAM("excludeteam"), EXCLUDE_OPS("excludeops"), ONCE("once"), SYNC("sync");

        private final String word;

        PlayOption(String word) {
            this.word = word;
        }
    }

    private ConditionalVideosCommands() {
    }

    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        ArgumentBuilder<CommandSourceStack, ?> playTargets = Commands.argument(TARGETS, EntityArgument.players());
        ConditionArguments.addTo(playTargets, (node, key) -> addPlayOptions(node, 0, EnumSet.noneOf(PlayOption.class), key));

        ArgumentBuilder<CommandSourceStack, ?> resetTargets = Commands.argument(TARGETS, EntityArgument.players())
                .then(Commands.literal("all").executes(context -> resetCommand(context, null, "all")));
        ConditionArguments.addTo(resetTargets, (node, key) -> node.executes(context -> {
            String condition = key.apply(context);
            return resetCommand(context, condition, condition);
        }));

        dispatcher.register(Commands.literal("conditionalvideos")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("play").then(playTargets))
                .then(Commands.literal("stop")
                        .then(Commands.argument(TARGETS, EntityArgument.players())
                                .executes(ConditionalVideosCommands::stopCommand)))
                .then(Commands.literal("pause")
                        .then(Commands.argument(TARGETS, EntityArgument.players())
                                .executes(ConditionalVideosCommands::pauseCommand)))
                .then(Commands.literal("reset").then(resetTargets))
                .then(SetCommands.node()));
    }

    // After the condition each option can be given or left out, in a fixed order, so only what may still follow
    // is suggested and every value keeps its own name.
    private static void addPlayOptions(ArgumentBuilder<CommandSourceStack, ?> node, int first, Set<PlayOption> chosen,
                                       Function<CommandContext<CommandSourceStack>, String> key) {
        node.executes(context -> playCommand(context, key.apply(context), chosen));
        PlayOption[] options = PlayOption.values();
        for (int index = first; index < options.length; index++) {
            PlayOption option = options[index];
            Set<PlayOption> next = EnumSet.copyOf(chosen.isEmpty() ? EnumSet.of(option) : chosen);
            next.add(option);
            ArgumentBuilder<CommandSourceStack, ?> word = Commands.literal(option.word);
            ArgumentBuilder<CommandSourceStack, ?> last = switch (option) {
                case DELAY -> Commands.argument(DELAY_SECONDS, FloatArgumentType.floatArg(0f));
                case EXCLUDE -> Commands.argument(EXCLUDED_PLAYERS, EntityArgument.players());
                case EXCLUDE_TEAM -> Commands.argument(EXCLUDED_TEAM, TeamArgument.team());
                default -> word;
            };
            addPlayOptions(last, index + 1, next, key);
            if (last != word) {
                word.then(last);
            }
            node.then(word);
        }
    }

    private static PlayOptions readOptions(CommandContext<CommandSourceStack> context, Set<PlayOption> chosen) throws CommandSyntaxException {
        Float delay = chosen.contains(PlayOption.DELAY) ? Float.valueOf(FloatArgumentType.getFloat(context, DELAY_SECONDS)) : null;
        Set<String> players = chosen.contains(PlayOption.EXCLUDE)
                ? EntityArgument.getOptionalPlayers(context, EXCLUDED_PLAYERS).stream().map(ServerPlayer::getScoreboardName).collect(Collectors.toSet()) : Set.of();
        Set<String> teams = chosen.contains(PlayOption.EXCLUDE_TEAM) ? Set.of(TeamArgument.getTeam(context, EXCLUDED_TEAM).getName()) : Set.of();
        boolean once = chosen.contains(PlayOption.ONCE);
        // A player written by name is chosen on purpose, which forces them past exclusions; @a and the like respect them.
        boolean named = !context.getArgument(TARGETS, EntitySelector.class).usesSelector();
        return new PlayOptions(delay, players, teams, chosen.contains(PlayOption.EXCLUDE_OPS), named, !once, once, chosen.contains(PlayOption.SYNC));
    }

    // The file decides; what the command adds changes it for this one playback. Whoever still has a video is left
    // alone, so running it again only reaches the rest.
    private static int playCommand(CommandContext<CommandSourceStack> context, String condition, Set<PlayOption> chosen) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS);
        ServerDebug.countCommand();
        PlayOptions options = readOptions(context, chosen);
        ConditionalVideosConfig.ConditionConfig resolved = ConditionRegistry.resolve(loadActiveConfig(source.getServer()), condition);
        if (resolved == null || resolved.resolvedPlaylist().isEmpty()) {
            DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'play {}' for {}: the condition has no videos.", source.getTextName(), condition, names(targets));
            source.sendFailure(Component.translatable("command.conditionalvideos.play.invalid", condition));
            return 0;
        }

        int sent = 0;
        int waiting = 0;
        int excluded = 0;
        int watched = 0;
        int busy = 0;
        for (ServerPlayer target : targets) {
            if (ServerConditionDispatcher.isBusy(target, condition)) {
                busy++;
                continue;
            }
            switch (ServerConditionDispatcher.play(target, condition, options)) {
                case SENT -> sent++;
                case WAITING -> waiting++;
                case EXCLUDED -> excluded++;
                case WATCHED -> watched++;
                default -> busy++;
            }
        }
        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'play {}' {} for {}: {} sent, {} waiting, {} excluded, {} watched, {} busy.",
                source.getTextName(), condition, chosen, names(targets), sent, waiting, excluded, watched, busy);
        int sentCount = sent;
        int waitingCount = waiting;
        if (sentCount > 0) {
            source.sendSuccess(() -> Component.translatable("command.conditionalvideos.play.success", condition, sentCount), true);
        }
        if (waitingCount > 0) {
            float seconds = options.delayTicks(resolved) / 20f;
            source.sendSuccess(() -> Component.translatable("command.conditionalvideos.play.waiting", condition, seconds, waitingCount), true);
        }
        boolean started = sentCount + waitingCount > 0;
        reportSkipped(source, "play.skipped_excluded", condition, excluded, started);
        reportSkipped(source, "play.skipped_watched", condition, watched, started);
        reportSkipped(source, "play.skipped_busy", condition, busy, started);
        return sentCount + waitingCount;
    }

    // Only the reasons that happened, each in its own line: an error when nobody got the video.
    private static void reportSkipped(CommandSourceStack source, String message, String condition, int count, boolean started) {
        if (count == 0) {
            return;
        }
        Component text = Component.translatable("command.conditionalvideos." + message, condition, count);
        if (started) {
            source.sendSuccess(() -> text, true);
        } else {
            source.sendFailure(text);
        }
    }

    private static int resetCommand(CommandContext<CommandSourceStack> context, String key, String label) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS);
        ServerDebug.countCommand();
        int reset = 0;
        for (ServerPlayer target : targets) {
            if (WatchedConditions.forget(source.getServer(), target, key) > 0) {
                reset++;
            }
        }
        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'reset {}' for {}: {} had something to clear.", source.getTextName(), label, names(targets), reset);
        if (reset == 0) {
            source.sendFailure(Component.translatable("command.conditionalvideos.reset.nothing", label));
            return 0;
        }
        int count = reset;
        source.sendSuccess(() -> Component.translatable("command.conditionalvideos.reset.success", label, count), true);
        return count;
    }

    private static int stopCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS);
        ServerDebug.countCommand();
        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'stop' for {}.", source.getTextName(), names(targets));
        for (ServerPlayer target : targets) {
            PlaybackControlNetworking.sendStop(target);
        }
        int count = targets.size();
        source.sendSuccess(() -> Component.translatable("command.conditionalvideos.stop.success", count), true);
        return count;
    }

    private static int pauseCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS);
        ServerDebug.countCommand();
        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'pause' for {}.", source.getTextName(), names(targets));
        for (ServerPlayer target : targets) {
            PlaybackControlNetworking.sendPause(target);
        }
        int count = targets.size();
        source.sendSuccess(() -> Component.translatable("command.conditionalvideos.pause.success", count), true);
        return count;
    }

    private static List<String> names(Collection<ServerPlayer> players) {
        return players.stream().map(ServerPlayer::getScoreboardName).toList();
    }

    private static ConditionalVideosConfig loadActiveConfig(MinecraftServer server) {
        return ConditionFiles.active(server.isDedicatedServer()).config();
    }
}
