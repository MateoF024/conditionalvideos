package org.mateof24.conditionalvideos.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.ServerDebug;
import org.mateof24.conditionalvideos.runtime.PlaybackControlClient;

public final class PlaybackControlNetworking {
    private static final ResourceLocation CONTROL_PLAY_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "control_play");
    private static final ResourceLocation CONTROL_STOP_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "control_stop");
    private static final ResourceLocation CONTROL_PAUSE_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "control_pause");

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
        }
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
}
