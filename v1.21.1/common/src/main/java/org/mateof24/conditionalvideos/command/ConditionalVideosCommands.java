package org.mateof24.conditionalvideos.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.ServerDebug;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public final class ConditionalVideosCommands {
    private static final SuggestionProvider<CommandSourceStack> CONDITION_SUGGESTIONS = (context, builder) -> {
        try {
            ConditionalVideosConfig config = loadActiveConfig(context.getSource().getServer());
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (String key : ConditionRegistry.listPlayableKeys(config)) {
                if (key.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(key);
                }
            }
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    };

    private ConditionalVideosCommands() {
    }

    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("conditionalvideos")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("play")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("condition", StringArgumentType.greedyString())
                                        .suggests(CONDITION_SUGGESTIONS)
                                        .executes(ConditionalVideosCommands::playCommand))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ConditionalVideosCommands::stopCommand)))
                .then(Commands.literal("pause")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ConditionalVideosCommands::pauseCommand))));
    }

    private static int playCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String condition = StringArgumentType.getString(context, "condition").trim();
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");

        MinecraftServer server = source.getServer();
        ServerDebug.countCommand();
        ConditionalVideosConfig.ConditionConfig resolved =
                ConditionRegistry.resolve(loadActiveConfig(server), condition);
        if (resolved == null || resolved.resolvedPlaylist().isEmpty()) {
            DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'play {}' for {}: the condition has no videos.",
                    source.getTextName(), condition, names(targets));
            source.sendFailure(Component.translatable("command.conditionalvideos.play.invalid", condition));
            return 0;
        }

        DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'play {}' for {}.", source.getTextName(), condition, names(targets));
        for (ServerPlayer target : targets) {
            PlaybackControlNetworking.sendPlay(target, condition);
        }
        int count = targets.size();
        source.sendSuccess(() -> Component.translatable("command.conditionalvideos.play.success", condition, count), true);
        return count;
    }

    private static int stopCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
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
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
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
        Path directory = server.getServerDirectory();
        if (server.isDedicatedServer()) {
            return ConditionalVideosConfig.loadServer(directory);
        }
        return ConditionalVideosConfig.load(ConditionalVideosConfig.clientConfigPath(directory));
    }
}
