package org.mateof24.conditionalvideos.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.TeamArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.api.PlayOptions;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionEdits;
import org.mateof24.conditionalvideos.config.ConditionFiles;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.ServerDebug;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.world.WaitingRoom;
import org.mateof24.conditionalvideos.world.WatchedConditions;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ConditionalVideosCommands {
    private static final String TARGETS = "targets";
    private static final String DELAY_SECONDS = "seconds";
    private static final String EXCLUDED_PLAYERS = "players";
    private static final String EXCLUDED_TEAM = "team";
    private static final List<String> PLAYER_SELECTORS = List.of("@a", "@p", "@r", "@s");
    private static final int MAX_NAME_LENGTH = 16;

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
                .then(Commands.literal("waitingroom")
                        .then(Commands.literal("start")
                                .executes(context -> roomStartCommand(context, false))
                                .then(Commands.literal("force").executes(context -> roomStartCommand(context, true))))
                        .then(Commands.literal("join")
                                .executes(ConditionalVideosCommands::roomJoinCommand)
                                .then(Commands.argument(TARGETS, EntityArgument.players())
                                        .suggests(playerSuggestions(server -> onlineNames(server, player -> !WaitingRoom.isMember(player))))
                                        .executes(ConditionalVideosCommands::roomJoinTargetsCommand)))
                        .then(Commands.literal("leave")
                                .executes(ConditionalVideosCommands::roomLeaveCommand)
                                .then(Commands.argument(TARGETS, EntityArgument.players())
                                        .suggests(playerSuggestions(server -> onlineNames(server, WaitingRoom::isMember)))
                                        .executes(ConditionalVideosCommands::roomLeaveTargetsCommand)))
                        .then(Commands.literal("add")
                                .then(Commands.argument(TARGETS, EntityArgument.players())
                                        .suggests(playerSuggestions(ConditionalVideosCommands::unlistedNames))
                                        .executes(ConditionalVideosCommands::roomAddCommand)))
                        .then(Commands.literal("remove")
                                .then(Commands.argument(TARGETS, EntityArgument.players())
                                        .suggests(playerSuggestions(ConditionalVideosCommands::removableNames))
                                        .executes(ConditionalVideosCommands::roomRemoveCommand))))
                .then(SetCommands.node()));
    }

    // Only who the command can act on, and the selectors; once a selector is being written, the game's own take over.
    private static SuggestionProvider<CommandSourceStack> playerSuggestions(Function<MinecraftServer, Stream<String>> names) {
        return (context, builder) -> {
            if (builder.getRemaining().startsWith("@")) {
                return EntityArgument.players().listSuggestions(context, builder);
            }
            return SharedSuggestionProvider.suggest(Stream.concat(names.apply(context.getSource().getServer()), PLAYER_SELECTORS.stream()), builder);
        };
    }

    private static Stream<String> onlineNames(MinecraftServer server, Predicate<ServerPlayer> filter) {
        return server.getPlayerList().getPlayers().stream().filter(filter).map(ServerPlayer::getScoreboardName);
    }

    private static Stream<String> unlistedNames(MinecraftServer server) {
        Set<String> listed = WaitingRoom.writtenNames(server).stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return onlineNames(server, player -> !listed.contains(player.getScoreboardName().toLowerCase(Locale.ROOT)));
    }

    // Who is on the list, connected or not, and whoever is in the room without being on it.
    private static Stream<String> removableNames(MinecraftServer server) {
        return Stream.concat(WaitingRoom.writtenNames(server).stream(), onlineNames(server, WaitingRoom::isMember)).distinct();
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

    private static int roomStartCommand(CommandContext<CommandSourceStack> context, boolean force) {
        CommandSourceStack source = context.getSource();
        ServerDebug.countCommand();
        WaitingRoom.StartResult result = WaitingRoom.requestStart(source.getServer(), force);
        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'waitingroom start{}': {}.", source.getTextName(), force ? " force" : "", result);
        if (result == WaitingRoom.StartResult.STARTED) {
            source.sendSuccess(() -> Component.translatable("command.conditionalvideos.waitingroom.started"), true);
            return 1;
        }
        source.sendFailure(switch (result) {
            case OFF -> roomText("off");
            case FINISHED -> roomText("finished");
            case ALREADY_STARTED -> roomText("already_started");
            case EMPTY -> roomText("empty");
            default -> roomText("not_ready", WaitingRoom.progress(source.getServer()));
        });
        return 0;
    }

    private static int roomJoinCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerDebug.countCommand();
        WaitingRoom.AddResult result = WaitingRoom.add(source.getServer(), player);
        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'waitingroom join': {}.", source.getTextName(), result);
        if (result == WaitingRoom.AddResult.ENTERED) {
            source.sendSuccess(() -> roomText("joined"), true);
            return 1;
        }
        source.sendFailure(switch (result) {
            case OFF -> roomText("off");
            case FINISHED -> roomText("finished");
            case ALREADY_IN -> roomText("you_already_in");
            default -> roomText("you_left_out");
        });
        return 0;
    }

    private static int roomJoinTargetsCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS);
        ServerDebug.countCommand();
        Map<WaitingRoom.AddResult, Integer> results = new EnumMap<>(WaitingRoom.AddResult.class);
        for (ServerPlayer target : targets) {
            results.merge(WaitingRoom.add(source.getServer(), target), 1, Integer::sum);
        }
        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'waitingroom join' for {}: {}.", source.getTextName(), names(targets), results);
        int entered = results.getOrDefault(WaitingRoom.AddResult.ENTERED, 0);
        if (entered > 0) {
            source.sendSuccess(() -> roomText("entered", entered), true);
        }
        reportRoomResults(source, results, entered > 0);
        return entered;
    }

    private static int roomLeaveCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerDebug.countCommand();
        boolean left = WaitingRoom.leaveByCommand(player);
        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'waitingroom leave': {}.", source.getTextName(), left ? "left" : "was not in it");
        if (!left) {
            source.sendFailure(roomText("you_not_in"));
            return 0;
        }
        source.sendSuccess(() -> roomText("left"), true);
        return 1;
    }

    private static int roomLeaveTargetsCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS);
        ServerDebug.countCommand();
        int left = 0;
        for (ServerPlayer target : targets) {
            if (WaitingRoom.leaveByCommand(target)) {
                left++;
            }
        }
        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'waitingroom leave' for {}: {} left.", source.getTextName(), names(targets), left);
        int count = left;
        if (count > 0) {
            source.sendSuccess(() -> roomText("players_left", count), true);
        }
        reportRoomSkipped(source, "not_in", targets.size() - count, count > 0);
        return count;
    }

    // The list lives in the file, so a name written out may belong to someone offline; a selector only reaches who is
    // connected. A name is at most 16 characters: anything longer was a UUID, which only finds someone connected.
    private static List<RoomTarget> listTargets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        boolean selector = context.getArgument(TARGETS, EntitySelector.class).usesSelector();
        Collection<ServerPlayer> online = selector ? EntityArgument.getPlayers(context, TARGETS) : EntityArgument.getOptionalPlayers(context, TARGETS);
        if (!online.isEmpty()) {
            return online.stream().map(player -> new RoomTarget(player.getScoreboardName(), player)).toList();
        }
        List<ParsedCommandNode<CommandSourceStack>> nodes = context.getNodes();
        String written = new StringReader(nodes.get(nodes.size() - 1).getRange().get(context.getInput())).readString();
        if (written.length() > MAX_NAME_LENGTH) {
            throw EntityArgument.NO_PLAYERS_FOUND.create();
        }
        return List.of(new RoomTarget(written, null));
    }

    private record RoomTarget(String name, ServerPlayer online) {
    }

    // Writes them on the file's list; whoever is connected and outside goes in at once.
    private static int roomAddCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        List<RoomTarget> targets = listTargets(context);
        ServerDebug.countCommand();
        List<String> names = targets.stream().map(RoomTarget::name).toList();
        int added;
        try {
            added = ConditionEdits.addRoomPlayers(ConditionFiles.active(server.isDedicatedServer()), names).size();
        } catch (ConditionEdits.Refused refused) {
            DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'waitingroom add' for {}: refused ({}).", source.getTextName(), names, refused.key);
            source.sendFailure(Component.translatable(refused.key, refused.args));
            return 0;
        }
        if (added > 0) {
            ConfigSyncNetworking.resendConditions(server);
        }
        Map<WaitingRoom.AddResult, Integer> results = new EnumMap<>(WaitingRoom.AddResult.class);
        for (RoomTarget target : targets) {
            if (target.online() != null) {
                results.merge(WaitingRoom.add(server, target.online()), 1, Integer::sum);
            }
        }
        int entered = results.getOrDefault(WaitingRoom.AddResult.ENTERED, 0);
        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'waitingroom add' for {}: {} written, {}.", source.getTextName(), names, added, results);
        int written = added;
        boolean done = written > 0 || entered > 0;
        if (written > 0) {
            source.sendSuccess(() -> roomText("added", written), true);
        }
        if (entered > 0) {
            source.sendSuccess(() -> roomText("entered", entered), true);
        }
        reportRoomSkipped(source, "already_listed", targets.size() - written, done);
        reportRoomResults(source, results, done);
        return Math.max(written, entered);
    }

    // Takes them off the file's list, and out of the room if they are in it.
    private static int roomRemoveCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        List<RoomTarget> targets = listTargets(context);
        ServerDebug.countCommand();
        List<String> names = targets.stream().map(RoomTarget::name).toList();
        Set<String> removed;
        try {
            removed = ConditionEdits.removeRoomPlayers(ConditionFiles.active(server.isDedicatedServer()), names);
        } catch (ConditionEdits.Refused refused) {
            DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'waitingroom remove' for {}: refused ({}).", source.getTextName(), names, refused.key);
            source.sendFailure(Component.translatable(refused.key, refused.args));
            return 0;
        }
        if (!removed.isEmpty()) {
            ConfigSyncNetworking.resendConditions(server);
        }
        int expelled = 0;
        int byTeam = 0;
        int notListed = 0;
        for (RoomTarget target : targets) {
            if (target.online() != null && WaitingRoom.remove(server, target.online())) {
                expelled++;
            }
            if (WaitingRoom.isExpected(server, target.name())) {
                byTeam++;
            } else if (!removed.contains(target.name().toLowerCase(Locale.ROOT))) {
                notListed++;
            }
        }
        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'waitingroom remove' for {}: {} erased, {} expelled, {} still expected by team.", source.getTextName(), names, removed.size(), expelled, byTeam);
        int erased = removed.size();
        int out = expelled;
        boolean done = erased > 0 || out > 0;
        if (erased > 0) {
            source.sendSuccess(() -> roomText("removed", erased), true);
        }
        if (out > 0) {
            source.sendSuccess(() -> roomText("expelled", out), true);
        }
        reportRoomSkipped(source, "not_listed", notListed, done);
        reportRoomSkipped(source, "still_by_team", byTeam, done);
        return Math.max(erased, out);
    }

    private static void reportRoomResults(CommandSourceStack source, Map<WaitingRoom.AddResult, Integer> results, boolean done) {
        reportRoomSkipped(source, "off", results.getOrDefault(WaitingRoom.AddResult.OFF, 0), done);
        reportRoomSkipped(source, "finished", results.getOrDefault(WaitingRoom.AddResult.FINISHED, 0), done);
        reportRoomSkipped(source, "already_in", results.getOrDefault(WaitingRoom.AddResult.ALREADY_IN, 0), done);
        reportRoomSkipped(source, "left_out", results.getOrDefault(WaitingRoom.AddResult.LEFT_OUT, 0), done);
    }

    // Only the reasons that happened, each in its own line: an error when the command did nothing else.
    private static void reportRoomSkipped(CommandSourceStack source, String message, int count, boolean done) {
        if (count == 0) {
            return;
        }
        Component text = roomText(message, count);
        if (done) {
            source.sendSuccess(() -> text, true);
        } else {
            source.sendFailure(text);
        }
    }

    private static Component roomText(String message, Object... arguments) {
        return Component.translatable("command.conditionalvideos.waitingroom." + message, arguments);
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
