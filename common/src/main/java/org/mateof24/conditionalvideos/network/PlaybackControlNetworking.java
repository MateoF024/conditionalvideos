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
import org.mateof24.conditionalvideos.runtime.WaitingRoomClient;
import org.mateof24.conditionalvideos.world.PlaybackSync;
import org.mateof24.conditionalvideos.world.WaitingRoom;
import org.mateof24.conditionalvideos.world.PlayerExclusions;
import org.mateof24.conditionalvideos.world.WatchedConditions;

import java.util.ArrayList;
import java.util.Collection;
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
    private static final ResourceLocation EXCLUDED_LIST_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "excluded_list");
    private static final ResourceLocation CONTROL_PLAY_AT_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "control_play_at");
    private static final ResourceLocation POSITION_REQUEST_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "position_request");
    private static final ResourceLocation POSITION_REPORT_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "position_report");
    private static final ResourceLocation ROOM_STATE_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "room_state");
    private static final ResourceLocation ROOM_SNAPSHOT_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "room_snapshot");
    private static final ResourceLocation ROOM_READY_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "room_ready");
    private static final ResourceLocation ROOM_LEAVE_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "room_leave");
    private static final ResourceLocation ROOM_START_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "room_start");
    private static final ResourceLocation ROOM_DURATION_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "room_duration");
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
            NetworkManager.registerReceiver(NetworkManager.s2c(), CONTROL_PLAY_AT_PACKET, (buffer, context) -> {
                String conditionKey = buffer.readUtf();
                int entry = buffer.readVarInt();
                double seconds = buffer.readDouble();
                context.queue(() -> {
                    DebugLog.client(DebugLog.Area.NETWORK, "The server ordered to join '{}' at entry {}, {} s.", conditionKey, entry, seconds);
                    PlaybackControlClient.playAt(conditionKey, entry, seconds);
                });
            });
            NetworkManager.registerReceiver(NetworkManager.s2c(), POSITION_REQUEST_PACKET, (buffer, context) -> {
                String conditionKey = buffer.readUtf();
                context.queue(() -> PlaybackControlClient.reportPosition(conditionKey));
            });
            NetworkManager.registerReceiver(NetworkManager.s2c(), ROOM_STATE_PACKET, (buffer, context) -> {
                boolean inRoom = buffer.readBoolean();
                int phase = buffer.readVarInt();
                int current = buffer.readVarInt();
                int required = buffer.readVarInt();
                boolean operator = buffer.readBoolean();
                List<String> missing = readKeys(buffer);
                context.queue(() -> {
                    if (phase >= 0 && phase < WaitingRoom.Phase.values().length) {
                        WaitingRoomClient.onState(inRoom, WaitingRoom.Phase.values()[phase], current, required, operator, missing);
                    }
                });
            });
            NetworkManager.registerReceiver(NetworkManager.s2c(), ROOM_SNAPSHOT_PACKET, (buffer, context) -> {
                int serial = buffer.readVarInt();
                int entry = buffer.readVarInt();
                boolean paused = buffer.readBoolean();
                long time = buffer.readLong();
                long duration = buffer.readLong();
                boolean live = buffer.readBoolean();
                context.queue(() -> WaitingRoomClient.onSnapshot(serial, entry, paused, time, duration, live));
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
            NetworkManager.registerReceiver(NetworkManager.s2c(), EXCLUDED_LIST_PACKET, (buffer, context) -> {
                List<String> keys = readKeys(buffer);
                context.queue(() -> {
                    DebugLog.client(DebugLog.Area.NETWORK, "The server says this player is left out of {} condition(s): {}.", keys.size(), keys);
                    ActiveConfigResolver.setExcludedFromServer(keys);
                });
            });
            NetworkManager.registerReceiver(NetworkManager.s2c(), WATCHED_LIST_PACKET, (buffer, context) -> {
                List<String> keys = readKeys(buffer);
                context.queue(() -> {
                    if (!keys.isEmpty()) {
                        DebugLog.client(DebugLog.Area.NETWORK, "The server says these were already watched in this world: {}.", keys);
                    }
                    ActiveConfigResolver.setWatchedFromServer(keys);
                });
            });
        }
        NetworkManager.registerReceiver(NetworkManager.c2s(), ROOM_READY_PACKET, (buffer, context) -> context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                WaitingRoom.onReady(player);
            }
        }));
        NetworkManager.registerReceiver(NetworkManager.c2s(), ROOM_LEAVE_PACKET, (buffer, context) -> context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                WaitingRoom.onLeaveRequest(player);
            }
        }));
        NetworkManager.registerReceiver(NetworkManager.c2s(), ROOM_START_PACKET, (buffer, context) -> context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                WaitingRoom.onStartRequest(player);
            }
        }));
        NetworkManager.registerReceiver(NetworkManager.c2s(), ROOM_DURATION_PACKET, (buffer, context) -> {
            int entry = buffer.readVarInt();
            long millis = buffer.readLong();
            boolean live = buffer.readBoolean();
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    WaitingRoom.onDuration(player, entry, millis, live);
                }
            });
        });
        NetworkManager.registerReceiver(NetworkManager.c2s(), POSITION_REPORT_PACKET, (buffer, context) -> {
            String conditionKey = buffer.readUtf();
            boolean known = buffer.readBoolean();
            int entry = buffer.readVarInt();
            double seconds = buffer.readDouble();
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    PlaybackSync.onPosition(player.getServer(), player, conditionKey, known, entry, seconds);
                }
            });
        });
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
        PlayerExclusions.ensureDelivered(player, conditionKey);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(conditionKey);
        NetworkManager.sendToPlayer(player, CONTROL_PLAY_PACKET, buffer);
        PlaybackSessions.recordOrder(player, conditionKey);
        ServerDebug.countOrder(conditionKey);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a play order for '{}' to {}.", conditionKey, player.getScoreboardName());
    }

    public static void sendPlayAt(ServerPlayer player, String conditionKey, int entry, double seconds) {
        PlayerExclusions.ensureDelivered(player, conditionKey);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(conditionKey);
        buffer.writeVarInt(entry);
        buffer.writeDouble(seconds);
        NetworkManager.sendToPlayer(player, CONTROL_PLAY_AT_PACKET, buffer);
        PlaybackSessions.recordOrder(player, conditionKey);
        ServerDebug.countOrder(conditionKey);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent an order to join '{}' at entry {}, {} s to {}.", conditionKey, entry, seconds, player.getScoreboardName());
    }

    public static void sendRoomState(ServerPlayer player, boolean inRoom, WaitingRoom.Phase phase, int current, int required, boolean operator, List<String> missing) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBoolean(inRoom);
        buffer.writeVarInt(phase.ordinal());
        buffer.writeVarInt(current);
        buffer.writeVarInt(required);
        buffer.writeBoolean(operator);
        buffer.writeVarInt(missing.size());
        missing.forEach(buffer::writeUtf);
        NetworkManager.sendToPlayer(player, ROOM_STATE_PACKET, buffer);
    }

    public static void sendRoomSnapshot(ServerPlayer player, int serial, int entry, boolean paused, long time, long duration, boolean live) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(serial);
        buffer.writeVarInt(entry);
        buffer.writeBoolean(paused);
        buffer.writeLong(time);
        buffer.writeLong(duration);
        buffer.writeBoolean(live);
        NetworkManager.sendToPlayer(player, ROOM_SNAPSHOT_PACKET, buffer);
    }

    public static void sendRoomReady() {
        sendEmptyRoomPacket(ROOM_READY_PACKET);
    }

    public static void sendRoomLeave() {
        sendEmptyRoomPacket(ROOM_LEAVE_PACKET);
    }

    public static void sendRoomStart() {
        sendEmptyRoomPacket(ROOM_START_PACKET);
    }

    private static void sendEmptyRoomPacket(ResourceLocation id) {
        if (Minecraft.getInstance().getConnection() != null) {
            NetworkManager.sendToServer(id, new FriendlyByteBuf(Unpooled.buffer()));
        }
    }

    public static void sendRoomDuration(int entry, long millis, boolean live) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(entry);
        buffer.writeLong(millis);
        buffer.writeBoolean(live);
        NetworkManager.sendToServer(ROOM_DURATION_PACKET, buffer);
    }

    public static void sendPositionRequest(ServerPlayer player, String conditionKey) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(conditionKey);
        NetworkManager.sendToPlayer(player, POSITION_REQUEST_PACKET, buffer);
    }

    public static void sendPosition(String conditionKey, boolean known, int entry, double seconds) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(conditionKey);
        buffer.writeBoolean(known);
        buffer.writeVarInt(entry);
        buffer.writeDouble(seconds);
        NetworkManager.sendToServer(POSITION_REPORT_PACKET, buffer);
    }

    public static void sendStop(ServerPlayer player) {
        if (WaitingRoom.handleStop(player)) {
            return;
        }
        ServerConditionDispatcher.cancelDelayed(player);
        NetworkManager.sendToPlayer(player, CONTROL_STOP_PACKET, new FriendlyByteBuf(Unpooled.buffer()));
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a stop order to {}.", player.getScoreboardName());
    }

    public static void sendPause(ServerPlayer player) {
        if (WaitingRoom.handlePause(player)) {
            return;
        }
        NetworkManager.sendToPlayer(player, CONTROL_PAUSE_PACKET, new FriendlyByteBuf(Unpooled.buffer()));
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a pause toggle to {}.", player.getScoreboardName());
    }

    public static void sendExcluded(ServerPlayer player, Collection<String> keys) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(keys.size());
        keys.forEach(buffer::writeUtf);
        NetworkManager.sendToPlayer(player, EXCLUDED_LIST_PACKET, buffer);
    }

    public static void sendWatched(ServerPlayer player) {
        List<String> keys = WatchedConditions.listFor(player);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(keys.size());
        keys.forEach(buffer::writeUtf);
        NetworkManager.sendToPlayer(player, WATCHED_LIST_PACKET, buffer);
        if (!keys.isEmpty()) {
            DebugLog.server(DebugLog.Area.WORLD, "{} already watched in this world: {}.", player.getScoreboardName(), keys);
        }
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
