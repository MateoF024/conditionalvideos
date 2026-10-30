package org.mateof24.conditionalvideos.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.mateof24.conditionalvideos.config.CommonConfig;
import org.mateof24.conditionalvideos.config.ConditionEdits;
import org.mateof24.conditionalvideos.config.ConditionEdits.Property;
import org.mateof24.conditionalvideos.config.ConditionFiles;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.ServerDebug;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;

import java.io.IOException;
import java.util.List;

// /conditionalvideos set: changes one value in one file and sends the result to whoever is connected.
final class SetCommands {
    private static final String VIDEO = "video_index";
    private static final String VOLUME = "volume";
    private static final String SECONDS = "seconds";
    private static final String TEXT = "text";
    private static final String X = "x";
    private static final String Y = "y";
    private static final List<String> CORNERS = List.of("topLeft", "topRight", "bottomLeft", "bottomRight");

    private SetCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set");
        set.then(Commands.literal("defaultvolume")
                .then(Commands.argument(VOLUME, FloatArgumentType.floatArg(0f, 1f))
                        .executes(SetCommands::defaultVolume)));
        set.then(condition(Property.VOLUME, (node, key) -> node.then(video()
                .then(Commands.argument(VOLUME, FloatArgumentType.floatArg(0f, 1f))
                        .executes(context -> run(context, Property.VOLUME, key.apply(context),
                                video(context) + " " + FloatArgumentType.getFloat(context, VOLUME)))))));
        set.then(condition(Property.CONDITION_DELAY, (node, key) -> node.then(Commands.argument(SECONDS, FloatArgumentType.floatArg(0f))
                .executes(context -> run(context, Property.CONDITION_DELAY, key.apply(context),
                        String.valueOf(FloatArgumentType.getFloat(context, SECONDS)))))));
        set.then(condition(Property.START_AT, (node, key) -> node.then(video()
                .then(Commands.argument(SECONDS, FloatArgumentType.floatArg(0f))
                        .executes(context -> run(context, Property.START_AT, key.apply(context),
                                video(context) + " " + FloatArgumentType.getFloat(context, SECONDS)))))));
        set.then(text(Property.TITLE));
        set.then(text(Property.DESCRIPTION));
        set.then(position(Property.TITLE_POSITION));
        set.then(position(Property.DESCRIPTION_POSITION));
        return set;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> condition(Property property, ConditionArguments.Leaf leaf) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(property.literal);
        ConditionArguments.addTo(node, leaf);
        return node;
    }

    // <video_index> [at_position <x> <y>] <text>
    private static LiteralArgumentBuilder<CommandSourceStack> text(Property property) {
        return condition(property, (node, key) -> node.then(video()
                .then(Commands.literal(ConditionEdits.AT_POSITION)
                        .then(Commands.argument(X, StringArgumentType.word())
                                .then(Commands.argument(Y, StringArgumentType.word())
                                        .then(Commands.argument(TEXT, StringArgumentType.greedyString())
                                                .executes(context -> run(context, property, key.apply(context),
                                                        video(context) + " " + ConditionEdits.AT_POSITION + " " + StringArgumentType.getString(context, X) + " " + StringArgumentType.getString(context, Y)
                                                                + " " + StringArgumentType.getString(context, TEXT)))))))
                .then(Commands.argument(TEXT, StringArgumentType.greedyString())
                        .executes(context -> run(context, property, key.apply(context),
                                video(context) + " " + StringArgumentType.getString(context, TEXT))))));
    }

    // <video_index> <corner> or <video_index> <x> <y>
    private static LiteralArgumentBuilder<CommandSourceStack> position(Property property) {
        return condition(property, (node, key) -> {
            RequiredArgumentBuilder<CommandSourceStack, Integer> video = video();
            for (String corner : CORNERS) {
                video.then(Commands.literal(corner)
                        .executes(context -> run(context, property, key.apply(context), video(context) + " " + corner)));
            }
            video.then(Commands.argument(X, StringArgumentType.word())
                    .then(Commands.argument(Y, StringArgumentType.word())
                            .executes(context -> run(context, property, key.apply(context),
                                    video(context) + " " + StringArgumentType.getString(context, X) + " " + StringArgumentType.getString(context, Y)))));
            node.then(video);
        });
    }

    // No suggestions on purpose: a bare number would hide the name that says what it is.
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> video() {
        return Commands.argument(VIDEO, IntegerArgumentType.integer(1));
    }

    private static int video(CommandContext<CommandSourceStack> context) {
        return IntegerArgumentType.getInteger(context, VIDEO);
    }

    private static int run(CommandContext<CommandSourceStack> context, Property property, String condition, String arguments) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerDebug.countCommand();
        try {
            ConditionEdits.apply(ConditionFiles.active(server.isDedicatedServer()), property, condition, arguments);
            DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'set {} {} {}'.", source.getTextName(), property.literal, condition, arguments);
            ConfigSyncNetworking.resendConditions(server);
            source.sendSuccess(() -> Component.translatable("command.conditionalvideos.set.success", property.literal, condition), true);
            return 1;
        } catch (ConditionEdits.Refused refused) {
            DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'set {} {} {}': refused ({}).", source.getTextName(), property.literal, condition, arguments, refused.key);
            source.sendFailure(Component.translatable(refused.key, refused.args));
            return 0;
        }
    }

    private static int defaultVolume(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        float volume = FloatArgumentType.getFloat(context, VOLUME);
        ServerDebug.countCommand();
        try {
            CommonConfig.writeDefaultVolume(CommonConfig.configPath(), volume);
            DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'set defaultvolume {}'.", source.getTextName(), volume);
            ConfigSyncNetworking.resendCommonConfig(source.getServer());
            source.sendSuccess(() -> Component.translatable("command.conditionalvideos.set.default_volume", volume), true);
            return 1;
        } catch (IOException | RuntimeException exception) {
            DebugLog.server(DebugLog.Area.COMMAND, "{} ran 'set defaultvolume {}': {}.", source.getTextName(), volume, exception.toString());
            source.sendFailure(Component.translatable("command.conditionalvideos.set.write_failed", exception.getMessage()));
            return 0;
        }
    }
}
