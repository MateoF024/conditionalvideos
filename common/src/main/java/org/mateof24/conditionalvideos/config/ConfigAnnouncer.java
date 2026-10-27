package org.mateof24.conditionalvideos.config;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.world.PlayerRoles;

import java.util.ArrayList;
import java.util.List;

// Shows the problems of the conditions in the chat to whoever can fix them: the player in singleplayer,
// the host on LAN and the operators on a dedicated server. They get them when they join and again
// whenever the files change.
public final class ConfigAnnouncer {
    private static final int CHECK_TICKS = 40;
    private static final int MAX_LINES = 8;

    private static int ticks;
    private static int announcedGeneration = -1;
    private static String announcedSignature = "";

    private ConfigAnnouncer() {
    }

    public static void init() {
        LifecycleEvent.SERVER_STARTED.register(ConfigAnnouncer::onServerStarted);
        PlayerEvent.PLAYER_JOIN.register(ConfigAnnouncer::onJoin);
        TickEvent.SERVER_POST.register(ConfigAnnouncer::onServerTick);
    }

    private static void onServerStarted(MinecraftServer server) {
        ConditionFiles files = ConditionFiles.active(server.isDedicatedServer());
        ticks = 0;
        announcedGeneration = files.generation();
        announcedSignature = signature(files.notices());
    }

    private static void onJoin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || !canFix(server, player)) {
            return;
        }
        List<ConfigNotice> notices = new ArrayList<>(LegacyMigration.notices());
        notices.addAll(ConditionFiles.active(server.isDedicatedServer()).notices());
        if (!notices.isEmpty()) {
            send(player, notices);
            DebugLog.server(DebugLog.Area.CONFIG, "Showed {} config problem(s) to {}.", notices.size(), player.getScoreboardName());
        }
    }

    private static void onServerTick(MinecraftServer server) {
        if (++ticks < CHECK_TICKS) {
            return;
        }
        ticks = 0;
        ConditionFiles files = ConditionFiles.active(server.isDedicatedServer());
        int generation = files.generation();
        if (generation == announcedGeneration) {
            return;
        }
        announcedGeneration = generation;
        List<ConfigNotice> notices = files.notices();
        String signature = signature(notices);
        if (signature.equals(announcedSignature)) {
            return;
        }
        announcedSignature = signature;
        int told = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!canFix(server, player)) {
                continue;
            }
            if (notices.isEmpty()) {
                player.sendSystemMessage(Component.translatableWithFallback("conditionalvideos.notice.fixed",
                        "[ConditionalVideos] The conditions have no problems now.").withStyle(ChatFormatting.GREEN));
            } else {
                send(player, notices);
            }
            told++;
        }
        DebugLog.server(DebugLog.Area.CONFIG, "The conditions changed: {} problem(s), told {} player(s).", notices.size(), told);
    }

    private static boolean canFix(MinecraftServer server, ServerPlayer player) {
        return server.isDedicatedServer() ? PlayerRoles.isOperator(player) : PlayerRoles.isHost(server, player);
    }

    private static void send(ServerPlayer player, List<ConfigNotice> notices) {
        MutableComponent message = Component.translatableWithFallback("conditionalvideos.notice.header",
                "[ConditionalVideos] %s problem(s) in the conditions (details in the game log):", notices.size())
                .withStyle(ChatFormatting.GOLD);
        int shown = Math.min(MAX_LINES, notices.size());
        for (int i = 0; i < shown; i++) {
            ConfigNotice notice = notices.get(i);
            message.append(Component.literal("\n - ")
                    .append(Component.translatableWithFallback(notice.key(), notice.english(), notice.args()))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (notices.size() > shown) {
            message.append(Component.literal("\n ")
                    .append(Component.translatableWithFallback("conditionalvideos.notice.more", "... and %s more", notices.size() - shown))
                    .withStyle(ChatFormatting.GRAY));
        }
        player.sendSystemMessage(message);
    }

    private static String signature(List<ConfigNotice> notices) {
        StringBuilder signature = new StringBuilder();
        notices.forEach(notice -> signature.append(notice.text()).append('\n'));
        return signature.toString();
    }
}
