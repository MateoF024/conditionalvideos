package org.mateof24.conditionalvideos.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionFiles;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;

import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Function;

// How a command names a condition: its type, then what identifies it in that type, each a separate argument with
// its own name and suggestions (entityKilled <entity>, custom <condition>). The simple ones are just their name.
final class ConditionArguments {
    private ConditionArguments() {
    }

    // Finishes the branch of one condition type: what follows it, given how to read the condition key there.
    interface Leaf {
        void finish(ArgumentBuilder<CommandSourceStack, ?> node, Function<CommandContext<CommandSourceStack>, String> key);
    }

    static void addTo(ArgumentBuilder<CommandSourceStack, ?> parent, Leaf leaf) {
        for (String name : ConditionalVideosConfig.SIMPLE_CONDITIONS) {
            ArgumentBuilder<CommandSourceStack, ?> node = Commands.literal(name);
            leaf.finish(node, context -> name);
            parent.then(node);
        }
        namespaced(parent, leaf, ConditionRegistry.TYPE_ENTITY_KILLED, "entity");
        namespaced(parent, leaf, ConditionRegistry.TYPE_DEATH_BY_ENTITY, "entity");
        namespaced(parent, leaf, ConditionRegistry.TYPE_ADVANCEMENT, "advancement");
        namespaced(parent, leaf, ConditionRegistry.TYPE_DIMENSION, "dimension");
        namespaced(parent, leaf, ConditionRegistry.TYPE_ITEM_OBTAINED, "item");
        namespaced(parent, leaf, ConditionRegistry.TYPE_ITEM_CRAFTED, "item");
        namespaced(parent, leaf, ConditionRegistry.TYPE_RECIPE_UNLOCKED, "recipe");
        named(parent, leaf, ConditionRegistry.TYPE_SCOREBOARD, "objective");
        named(parent, leaf, ConditionRegistry.TYPE_CUSTOM, "condition");
        named(parent, leaf, ConditionRegistry.TYPE_WORLD_POSITION, "condition");
    }

    private static void namespaced(ArgumentBuilder<CommandSourceStack, ?> parent, Leaf leaf, String type, String argument) {
        RequiredArgumentBuilder<CommandSourceStack, ?> id = Commands.argument(argument, ResourceLocationArgument.id()).suggests(configured(type));
        leaf.finish(id, context -> type + "/" + ResourceLocationArgument.getId(context, argument));
        parent.then(Commands.literal(type).then(id));
    }

    // Quotes are only needed for an id with characters such as /, and the suggestion already brings them.
    private static void named(ArgumentBuilder<CommandSourceStack, ?> parent, Leaf leaf, String type, String argument) {
        RequiredArgumentBuilder<CommandSourceStack, ?> id = Commands.argument(argument, StringArgumentType.string()).suggests(configured(type));
        leaf.finish(id, context -> type + "/" + StringArgumentType.getString(context, argument));
        parent.then(Commands.literal(type).then(id));
    }

    // The conditions of this type that have a file, telling how many videos each one holds.
    private static SuggestionProvider<CommandSourceStack> configured(String type) {
        return (context, builder) -> {
            ConditionFiles files = ConditionFiles.active(context.getSource().getServer().isDedicatedServer());
            ConditionalVideosConfig config = files.config();
            String folder = ConditionRegistry.fileKey(type + "/");
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (String owned : new TreeSet<>(files.ownedConditions())) {
                if (!owned.startsWith(folder)) {
                    continue;
                }
                String id = owned.substring(folder.length());
                String shown = ConditionRegistry.TYPE_SCOREBOARD.equals(type) || ConditionRegistry.TYPE_CUSTOM.equals(type) || ConditionRegistry.TYPE_WORLD_POSITION.equals(type)
                        ? StringArgumentType.escapeIfRequired(id) : id;
                if (shown.toLowerCase(Locale.ROOT).startsWith(typed) || id.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    ConditionalVideosConfig.ConditionConfig condition = ConditionRegistry.resolve(config, type + "/" + id);
                    int videos = condition == null ? 0 : condition.resolvedPlaylist().size();
                    builder.suggest(shown, Component.translatable("command.conditionalvideos.suggest.videos", videos));
                }
            }
            return builder.buildFuture();
        };
    }
}
