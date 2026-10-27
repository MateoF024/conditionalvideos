package org.mateof24.conditionalvideos.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.ServerDebug;
import org.mateof24.conditionalvideos.runtime.PlaybackControlClient;
import org.mateof24.conditionalvideos.world.WatchedConditions;

import java.util.ArrayList;
import java.util.List;

public final class PlaybackControlNetworking {
    private static final ResourceLocation CONTROL_PLAY_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "control_play");
    private static final ResourceLocation CONTROL_STOP_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "control_stop");
    private static final ResourceLocation CONTROL_PAUSE_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "control_pause");
    private static final ResourceLocation WATCHED_LIST_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "watched_list");
    private static final ResourceLocation PLAYBACK_STATE_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "playback_state");

    private PlaybackControlNetworking() {
    }

    public static void init() {
        if (Platform.getEnvironment() == Env.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.s2c(), CONTROL_PLAY_PACKET, (buffer, context) -> {
                String conditionKey = buffer.readUtf();
                context.queue(() -> {
                    DebugLog.client(DebugLog.Area.NETWORK, "The server ordered to play '{}'.", conditionKey);
                    PlaybackControlClient.play(conditionKey);
                });
            });
            NetworkManager.registerReceiver(NetworkManager.s2c(), CONTROL_STOP_PACKET, (buffer, context) ->
                    context.queue(() -> {
                        DebugLog.client(DebugLog.Area.NETWORK, "The server ordered to stop playback.");
                        PlaybackControlClient.stop();
                    }));
            NetworkManager.registerReceiver(NetworkManager.s2c(), CONTROL_PAUSE_PACKET, (buffer, context) ->
                    context.queue(() -> {
                        DebugLog.client(DebugLog.Area.NETWORK, "The server ordered to toggle pause.");
                        PlaybackControlClient.togglePause();
                    }));
            NetworkManager.registerReceiver(NetworkManager.s2c(), WATCHED_LIST_PACKET, (buffer, context) -> {
                List<String> keys = readKeys(buffer);
                context.queue(() -> {
                    DebugLog.client(DebugLog.Area.NETWORK, "The server says {} condition(s) were already watched in this world: {}.",
                            keys.size(), keys);
                    ActiveConfigResolver.setWatchedFromServer(keys);
                });
            });
        }
        NetworkManager.registerReceiver(NetworkManager.c2s(), PLAYBACK_STATE_PACKET, (buffer, context) -> {
            String conditionKey = buffer.readUtf();
            boolean started = buffer.readBoolean();
            boolean once = buffer.readBoolean();
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    WatchedConditions.onPlayback(player, conditionKey, started, once);
                }
            });
        });
    }

    public static void sendPlay(ServerPlayer player, String conditionKey) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(conditionKey);
        NetworkManager.sendToPlayer(player, CONTROL_PLAY_PACKET, buffer);
        ServerDebug.countOrder(conditionKey);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a play order for '{}' to {}.", conditionKey, player.getScoreboardName());
    }

    public static void sendStop(ServerPlayer player) {
        NetworkManager.sendToPlayer(player, CONTROL_STOP_PACKET, new FriendlyByteBuf(Unpooled.buffer()));
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a stop order to {}.", player.getScoreboardName());
    }

    public static void sendPause(ServerPlayer player) {
        NetworkManager.sendToPlayer(player, CONTROL_PAUSE_PACKET, new FriendlyByteBuf(Unpooled.buffer()));
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a pause toggle to {}.", player.getScoreboardName());
    }

    public static void sendWatched(ServerPlayer player) {
        List<String> keys = WatchedConditions.listFor(player);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(keys.size());
        keys.forEach(buffer::writeUtf);
        NetworkManager.sendToPlayer(player, WATCHED_LIST_PACKET, buffer);
        DebugLog.server(DebugLog.Area.WORLD, "Sent {} the {} condition(s) they already watched in this world.",
                player.getScoreboardName(), keys.size());
    }

    // Tells the server a playback started or finished. Only servers that sent a watched list know this
    // message; once marks a natural start of a condition that plays once per world.
    public static void notifyPlayback(String conditionKey, boolean started, boolean once) {
        if (!ActiveConfigResolver.watchedFromServer() || Minecraft.getInstance().getConnection() == null) {
            return;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(conditionKey);
        buffer.writeBoolean(started);
        buffer.writeBoolean(once);
        NetworkManager.sendToServer(PLAYBACK_STATE_PACKET, buffer);
        DebugLog.client(DebugLog.Area.NETWORK, "Told the server '{}' {}.", conditionKey, started ? "started" : "finished");
    }

    private static List<String> readKeys(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            keys.add(buffer.readUtf());
        }
        return keys;
    }
}
