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
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.ServerDebug;
import org.mateof24.conditionalvideos.runtime.PlaybackControlClient;
import org.mateof24.conditionalvideos.world.PlaybackSessions;
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

    private static final int ALIVE_INTERVAL_TICKS = 100;
    private static final int OFF_SCREEN_LIMIT_TICKS = 40;

    // The playback this client is in (loading screen or video), until its playlist ends.
    private static String clientSession;
    private static int aliveTicks;
    private static int offScreenTicks;

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
            int phase = buffer.readVarInt();
            boolean natural = buffer.readBoolean();
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player && phase >= 0 && phase < PlaybackSessions.Phase.values().length) {
                    PlaybackSessions.onState(player, conditionKey, PlaybackSessions.Phase.values()[phase], natural);
                }
            });
        });
    }

    public static void sendPlay(ServerPlayer player, String conditionKey) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(conditionKey);
        NetworkManager.sendToPlayer(player, CONTROL_PLAY_PACKET, buffer);
        PlaybackSessions.recordOrder(player, conditionKey);
        ServerDebug.countOrder(conditionKey);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a play order for '{}' to {}.", conditionKey, player.getScoreboardName());
    }

    public static void sendStop(ServerPlayer player) {
        ServerConditionDispatcher.cancelDelayed(player);
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

    // Reports a phase of this client's playback (loading screen, video, end) and keeps the local session in
    // step: the pulse that keeps it alive on the server and the push immunity of a protected video. natural
    // marks what this client's own detection triggered. A delay only informs the server: nothing is on screen.
    public static void notifyPlayback(String conditionKey, PlaybackSessions.Phase phase, boolean natural, boolean protect) {
        if (phase == PlaybackSessions.Phase.ENDED) {
            if (conditionKey.equals(clientSession)) {
                resetClientSession();
            }
        } else if (phase != PlaybackSessions.Phase.DELAYED) {
            Minecraft minecraft = Minecraft.getInstance();
            clientSession = conditionKey;
            aliveTicks = 0;
            offScreenTicks = 0;
            PlaybackSessions.setClientImmune(protect && minecraft.player != null ? minecraft.player.getUUID() : null);
        }
        sendState(conditionKey, phase, natural);
    }

    // Sends the pulse while the session lasts, and ends it when no video screen has been open for a while
    // (another screen replaced the loading screen, so its end would never be reported).
    public static void tickClientSession(boolean videoOnScreen) {
        if (clientSession == null) {
            return;
        }
        offScreenTicks = videoOnScreen ? 0 : offScreenTicks + 1;
        if (offScreenTicks >= OFF_SCREEN_LIMIT_TICKS) {
            DebugLog.client(DebugLog.Area.PLAYLIST, "No video screen for {} ticks; ending the playback of '{}'.", OFF_SCREEN_LIMIT_TICKS, clientSession);
            notifyPlayback(clientSession, PlaybackSessions.Phase.ENDED, false, false);
            return;
        }
        if (++aliveTicks >= ALIVE_INTERVAL_TICKS) {
            aliveTicks = 0;
            sendState(clientSession, PlaybackSessions.Phase.ALIVE, false);
        }
    }

    public static void resetClientSession() {
        clientSession = null;
        PlaybackSessions.setClientImmune(null);
    }

    // Only servers that sent a watched list know this message.
    private static void sendState(String conditionKey, PlaybackSessions.Phase phase, boolean natural) {
        if (!ActiveConfigResolver.watchedFromServer() || Minecraft.getInstance().getConnection() == null) {
            return;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(conditionKey);
        buffer.writeVarInt(phase.ordinal());
        buffer.writeBoolean(natural);
        NetworkManager.sendToServer(PLAYBACK_STATE_PACKET, buffer);
        if (phase != PlaybackSessions.Phase.ALIVE) {
            DebugLog.client(DebugLog.Area.NETWORK, "Told the server '{}': {}.", conditionKey, phase);
        }
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
