package org.mateof24.conditionalvideos.network;

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
import java.util.Map;

// Playback control (play / stop / pause orders), the watched list and the playback start/end reports.
// Every payload travels through the single RawS2C / RawC2S types, so these handlers are MERGED into
// ConfigSyncNetworking's handler maps (registering the global receivers twice would crash); this class
// only contributes handlers + senders.
public final class PlaybackControlNetworking {
    private static final ResourceLocation CONTROL_PLAY =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "control_play");
    private static final ResourceLocation CONTROL_STOP =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "control_stop");
    private static final ResourceLocation CONTROL_PAUSE =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "control_pause");
    private static final ResourceLocation WATCHED_LIST =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "watched_list");
    private static final ResourceLocation EXCLUDED_LIST =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "excluded_list");
    private static final ResourceLocation CONTROL_PLAY_AT =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "control_play_at");
    private static final ResourceLocation POSITION_REQUEST =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "position_request");
    private static final ResourceLocation POSITION_REPORT =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "position_report");
    private static final ResourceLocation ROOM_STATE =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "room_state");
    private static final ResourceLocation ROOM_SNAPSHOT =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "room_snapshot");
    private static final ResourceLocation ROOM_READY =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "room_ready");
    private static final ResourceLocation ROOM_LEAVE =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "room_leave");
    private static final ResourceLocation ROOM_START =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "room_start");
    private static final ResourceLocation ROOM_DURATION =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "room_duration");
    private static final ResourceLocation PLAYBACK_STATE =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "playback_state");

    private static final int ALIVE_INTERVAL_TICKS = 100;
    private static final int OFF_SCREEN_LIMIT_TICKS = 40;

    // The playback this client is in (loading screen or video), until its playlist ends.
    private static String clientSession;
    private static int aliveTicks;
    private static int offScreenTicks;

    private PlaybackControlNetworking() {
    }

    public static void registerS2CHandlers(Map<ResourceLocation, NetworkHelper.S2CPacketHandler> s2cHandlers) {
        s2cHandlers.put(CONTROL_PLAY, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String conditionKey = buf.readUtf();
            DebugLog.client(DebugLog.Area.NETWORK, "The server ordered to play '{}'.", conditionKey);
            PlaybackControlClient.play(conditionKey);
        });
        s2cHandlers.put(CONTROL_PLAY_AT, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String conditionKey = buf.readUtf();
            int entry = buf.readVarInt();
            double seconds = buf.readDouble();
            DebugLog.client(DebugLog.Area.NETWORK, "The server ordered to join '{}' at entry {}, {} s.", conditionKey, entry, seconds);
            PlaybackControlClient.playAt(conditionKey, entry, seconds);
        });
        s2cHandlers.put(POSITION_REQUEST, data -> PlaybackControlClient.reportPosition(NetworkHelper.fromBytes(data).readUtf()));
        s2cHandlers.put(ROOM_STATE, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            boolean inRoom = buf.readBoolean();
            int phase = buf.readVarInt();
            int current = buf.readVarInt();
            int required = buf.readVarInt();
            boolean operator = buf.readBoolean();
            int missingCount = buf.readVarInt();
            List<String> missing = new ArrayList<>();
            for (int index = 0; index < missingCount; index++) {
                missing.add(buf.readUtf());
            }
            if (phase >= 0 && phase < WaitingRoom.Phase.values().length) {
                WaitingRoomClient.onState(inRoom, WaitingRoom.Phase.values()[phase], current, required, operator, missing);
            }
        });
        s2cHandlers.put(ROOM_SNAPSHOT, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            int serial = buf.readVarInt();
            int entry = buf.readVarInt();
            boolean paused = buf.readBoolean();
            long time = buf.readLong();
            long duration = buf.readLong();
            boolean live = buf.readBoolean();
            WaitingRoomClient.onSnapshot(serial, entry, paused, time, duration, live);
        });
        s2cHandlers.put(CONTROL_STOP, data -> {
            DebugLog.client(DebugLog.Area.NETWORK, "The server ordered to stop playback.");
            PlaybackControlClient.stop();
        });
        s2cHandlers.put(CONTROL_PAUSE, data -> {
            DebugLog.client(DebugLog.Area.NETWORK, "The server ordered to toggle pause.");
            PlaybackControlClient.togglePause();
        });
        s2cHandlers.put(EXCLUDED_LIST, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            int count = buf.readVarInt();
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                keys.add(buf.readUtf());
            }
            DebugLog.client(DebugLog.Area.NETWORK, "The server says this player is left out of {} condition(s): {}.", keys.size(), keys);
            ActiveConfigResolver.setExcludedFromServer(keys);
        });
        s2cHandlers.put(WATCHED_LIST, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            int count = buf.readVarInt();
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                keys.add(buf.readUtf());
            }
            if (!keys.isEmpty()) {
                DebugLog.client(DebugLog.Area.NETWORK, "The server says these were already watched in this world: {}.", keys);
            }
            ActiveConfigResolver.setWatchedFromServer(keys);
        });
    }

    public static void registerC2SHandlers(Map<ResourceLocation, NetworkHelper.C2SPacketHandler> c2sHandlers) {
        c2sHandlers.put(ROOM_READY, (data, player) -> WaitingRoom.onReady(player));
        c2sHandlers.put(ROOM_LEAVE, (data, player) -> WaitingRoom.onLeaveRequest(player));
        c2sHandlers.put(ROOM_START, (data, player) -> WaitingRoom.onStartRequest(player));
        c2sHandlers.put(ROOM_DURATION, (data, player) -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            int entry = buf.readVarInt();
            long millis = buf.readLong();
            boolean live = buf.readBoolean();
            WaitingRoom.onDuration(player, entry, millis, live);
        });
        c2sHandlers.put(POSITION_REPORT, (data, player) -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String conditionKey = buf.readUtf();
            boolean known = buf.readBoolean();
            int entry = buf.readVarInt();
            double seconds = buf.readDouble();
            PlaybackSync.onPosition(player.getServer(), player, conditionKey, known, entry, seconds);
        });
        c2sHandlers.put(PLAYBACK_STATE, (data, player) -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String conditionKey = buf.readUtf();
            int phase = buf.readVarInt();
            boolean natural = buf.readBoolean();
            if (phase >= 0 && phase < PlaybackSessions.Phase.values().length) {
                PlaybackSessions.onState(player, conditionKey, PlaybackSessions.Phase.values()[phase], natural);
            }
        });
    }

    public static void sendPlay(ServerPlayer player, String conditionKey) {
        PlayerExclusions.ensureDelivered(player, conditionKey);
        NetworkHelper.sendToPlayer(player, CONTROL_PLAY,
                NetworkHelper.toBytes(buf -> buf.writeUtf(conditionKey)));
        PlaybackSessions.recordOrder(player, conditionKey);
        ServerDebug.countOrder(conditionKey);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a play order for '{}' to {}.", conditionKey, player.getScoreboardName());
    }

    public static void sendPlayAt(ServerPlayer player, String conditionKey, int entry, double seconds) {
        PlayerExclusions.ensureDelivered(player, conditionKey);
        NetworkHelper.sendToPlayer(player, CONTROL_PLAY_AT, NetworkHelper.toBytes(buf -> {
            buf.writeUtf(conditionKey);
            buf.writeVarInt(entry);
            buf.writeDouble(seconds);
        }));
        PlaybackSessions.recordOrder(player, conditionKey);
        ServerDebug.countOrder(conditionKey);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent an order to join '{}' at entry {}, {} s to {}.", conditionKey, entry, seconds, player.getScoreboardName());
    }

    public static void sendRoomState(ServerPlayer player, boolean inRoom, WaitingRoom.Phase phase, int current, int required, boolean operator, List<String> missing) {
        NetworkHelper.sendToPlayer(player, ROOM_STATE, NetworkHelper.toBytes(buf -> {
            buf.writeBoolean(inRoom);
            buf.writeVarInt(phase.ordinal());
            buf.writeVarInt(current);
            buf.writeVarInt(required);
            buf.writeBoolean(operator);
            buf.writeVarInt(missing.size());
            missing.forEach(buf::writeUtf);
        }));
    }

    public static void sendRoomSnapshot(ServerPlayer player, int serial, int entry, boolean paused, long time, long duration, boolean live) {
        NetworkHelper.sendToPlayer(player, ROOM_SNAPSHOT, NetworkHelper.toBytes(buf -> {
            buf.writeVarInt(serial);
            buf.writeVarInt(entry);
            buf.writeBoolean(paused);
            buf.writeLong(time);
            buf.writeLong(duration);
            buf.writeBoolean(live);
        }));
    }

    public static void sendRoomReady() {
        sendEmptyRoomPacket(ROOM_READY);
    }

    public static void sendRoomLeave() {
        sendEmptyRoomPacket(ROOM_LEAVE);
    }

    public static void sendRoomStart() {
        sendEmptyRoomPacket(ROOM_START);
    }

    private static void sendEmptyRoomPacket(ResourceLocation id) {
        if (Minecraft.getInstance().getConnection() != null) {
            NetworkHelper.sendToServer(id, new byte[0]);
        }
    }

    public static void sendRoomDuration(int entry, long millis, boolean live) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        NetworkHelper.sendToServer(ROOM_DURATION, NetworkHelper.toBytes(buf -> {
            buf.writeVarInt(entry);
            buf.writeLong(millis);
            buf.writeBoolean(live);
        }));
    }

    public static void sendPositionRequest(ServerPlayer player, String conditionKey) {
        NetworkHelper.sendToPlayer(player, POSITION_REQUEST, NetworkHelper.toBytes(buf -> buf.writeUtf(conditionKey)));
    }

    public static void sendPosition(String conditionKey, boolean known, int entry, double seconds) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        NetworkHelper.sendToServer(POSITION_REPORT, NetworkHelper.toBytes(buf -> {
            buf.writeUtf(conditionKey);
            buf.writeBoolean(known);
            buf.writeVarInt(entry);
            buf.writeDouble(seconds);
        }));
    }

    public static void sendStop(ServerPlayer player) {
        if (WaitingRoom.handleStop(player)) {
            return;
        }
        ServerConditionDispatcher.cancelDelayed(player);
        NetworkHelper.sendToPlayer(player, CONTROL_STOP, new byte[0]);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a stop order to {}.", player.getScoreboardName());
    }

    public static void sendPause(ServerPlayer player) {
        if (WaitingRoom.handlePause(player)) {
            return;
        }
        NetworkHelper.sendToPlayer(player, CONTROL_PAUSE, new byte[0]);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent a pause toggle to {}.", player.getScoreboardName());
    }

    public static void sendExcluded(ServerPlayer player, Collection<String> keys) {
        NetworkHelper.sendToPlayer(player, EXCLUDED_LIST, NetworkHelper.toBytes(buf -> {
            buf.writeVarInt(keys.size());
            keys.forEach(buf::writeUtf);
        }));
    }

    public static void sendWatched(ServerPlayer player) {
        List<String> keys = WatchedConditions.listFor(player);
        NetworkHelper.sendToPlayer(player, WATCHED_LIST, NetworkHelper.toBytes(buf -> {
            buf.writeVarInt(keys.size());
            keys.forEach(buf::writeUtf);
        }));
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
        NetworkHelper.sendToServer(PLAYBACK_STATE, NetworkHelper.toBytes(buf -> {
            buf.writeUtf(conditionKey);
            buf.writeVarInt(phase.ordinal());
            buf.writeBoolean(natural);
        }));
        if (phase != PlaybackSessions.Phase.ALIVE) {
            DebugLog.client(DebugLog.Area.NETWORK, "Told the server '{}': {}.", conditionKey, phase);
        }
    }
}
