package org.mateof24.conditionalvideos.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.ServerDebug;
import org.mateof24.conditionalvideos.runtime.PlaybackControlClient;
import org.mateof24.conditionalvideos.world.WatchedConditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Playback control (play / stop / pause orders), the watched list and the playback start/end reports.
// Every payload travels through the single RawS2C / RawC2S types, so these handlers are MERGED into
// ConfigSyncNetworking's handler maps (registering the global receivers twice would crash); this class
// only contributes handlers + senders.
public final class PlaybackControlNetworking {
    private static final Identifier CONTROL_PLAY =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "control_play");
    private static final Identifier CONTROL_STOP =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "control_stop");
    private static final Identifier CONTROL_PAUSE =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "control_pause");
    private static final Identifier WATCHED_LIST =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "watched_list");
    private static final Identifier PLAYBACK_STATE =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "playback_state");

    private PlaybackControlNetworking() {
    }

    public static void registerS2CHandlers(Map<Identifier, NetworkHelper.S2CPacketHandler> s2cHandlers) {
        s2cHandlers.put(CONTROL_PLAY, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String conditionKey = buf.readUtf();
            DebugLog.client(DebugLog.Area.NETWORK, "The server ordered to play '{}'.", conditionKey);
            PlaybackControlClient.play(conditionKey);
        });
        s2cHandlers.put(CONTROL_STOP, data -> {
            DebugLog.client(DebugLog.Area.NETWORK, "The server ordered to stop playback.");
            PlaybackControlClient.stop();
        });
        s2cHandlers.put(CONTROL_PAUSE, data -> {
            DebugLog.client(DebugLog.Area.NETWORK, "The server ordered to toggle pause.");
            PlaybackControlClient.togglePause();
        });
        s2cHandlers.put(WATCHED_LIST, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            int count = buf.readVarInt();
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                keys.add(buf.readUtf());
            }
            DebugLog.client(DebugLog.Area.NETWORK, "The server says {} condition(s) were already watched in this world: {}.",
                    keys.size(), keys);
            ActiveConfigResolver.setWatchedFromServer(keys);
        });
    }

    public static void registerC2SHandlers(Map<Identifier, NetworkHelper.C2SPacketHandler> c2sHandlers) {
        c2sHandlers.put(PLAYBACK_STATE, (data, player) -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String conditionKey = buf.readUtf();
            boolean started = buf.readBoolean();
            boolean once = buf.readBoolean();
            WatchedConditions.onPlayback(player, conditionKey, started, once);
        });
    }

    public static void sendPlay(ServerPlayer player, String conditionKey) {
        NetworkHelper.sendToPlayer(player, CONTROL_PLAY,
                NetworkHelper.toBytes(buf -> buf.writeUtf(conditionKey)));
        ServerDebug.countOrder(conditionKey);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a play order for '{}' to {}.", conditionKey, player.getScoreboardName());
    }

    public static void sendStop(ServerPlayer player) {
        NetworkHelper.sendToPlayer(player, CONTROL_STOP, new byte[0]);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a stop order to {}.", player.getScoreboardName());
    }

    public static void sendPause(ServerPlayer player) {
        NetworkHelper.sendToPlayer(player, CONTROL_PAUSE, new byte[0]);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a pause toggle to {}.", player.getScoreboardName());
    }

    public static void sendWatched(ServerPlayer player) {
        List<String> keys = WatchedConditions.listFor(player);
        NetworkHelper.sendToPlayer(player, WATCHED_LIST, NetworkHelper.toBytes(buf -> {
            buf.writeVarInt(keys.size());
            keys.forEach(buf::writeUtf);
        }));
        DebugLog.server(DebugLog.Area.WORLD, "Sent {} the {} condition(s) they already watched in this world.",
                player.getScoreboardName(), keys.size());
    }

    // Tells the server a playback started or finished. Only servers that sent a watched list know this
    // message; once marks a natural start of a condition that plays once per world.
    public static void notifyPlayback(String conditionKey, boolean started, boolean once) {
        if (!ActiveConfigResolver.watchedFromServer() || Minecraft.getInstance().getConnection() == null) {
            return;
        }
        NetworkHelper.sendToServer(PLAYBACK_STATE, NetworkHelper.toBytes(buf -> {
            buf.writeUtf(conditionKey);
            buf.writeBoolean(started);
            buf.writeBoolean(once);
        }));
        DebugLog.client(DebugLog.Area.NETWORK, "Told the server '{}' {}.", conditionKey, started ? "started" : "finished");
    }
}
