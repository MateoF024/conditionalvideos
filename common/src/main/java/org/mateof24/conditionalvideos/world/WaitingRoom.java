package org.mateof24.conditionalvideos.world;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.VideoEntry;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.WaitingRoomConfig;
import org.mateof24.conditionalvideos.config.WaitingRoomText;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// The firstJoin waiting room, run entirely by the server with no WaterMedia classes: open, ready, starting behind a
// barrier of preloaded clients, playing on the server's clock (latecomers join where it is), finished. A firstJoin
// that does not repeat is spent for good once it finishes; a repeatable one opens a new cycle for the next player.
public final class WaitingRoom {
    public enum Phase { OPEN, READY, STARTING, PLAYING, FINISHED }

    public enum StartResult { STARTED, OFF, FINISHED, ALREADY_STARTED, EMPTY, NOT_READY }

    public enum AddResult { ENTERED, OFF, FINISHED, ALREADY_IN, LEFT_OUT }

    private static final String KEY = ConditionRegistry.KEY_FIRST_JOIN;
    private static final String START_COMMAND = "/conditionalvideos waitingroom start";
    private static final long BARRIER_MILLIS = 15_000L;
    private static final long CROSSFADE_MILLIS = 2_000L;
    // How long an entry nobody could report a duration for stays, so a broken source cannot hold the session.
    private static final long UNKNOWN_DURATION_MILLIS = 120_000L;
    private static final int SECOND_TICKS = 20;

    private static final Set<UUID> MEMBERS = new LinkedHashSet<>();
    private static final Set<UUID> READY_PLAYERS = new HashSet<>();
    private static final Map<Integer, Long> DURATIONS = new HashMap<>();
    private static final Set<Integer> LIVE_ENTRIES = new HashSet<>();

    private static Phase phase = Phase.OPEN;
    private static long barrierStart;
    private static boolean operatorsTold;
    private static int entry;
    private static int serial;
    private static long entryStart;
    private static long pausedAt;
    private static int ticks;
    private static String lastShown;

    private WaitingRoom() {
    }

    public static void init() {
        PlayerEvent.PLAYER_JOIN.register(WaitingRoom::onJoin);
        PlayerEvent.PLAYER_QUIT.register(WaitingRoom::onQuit);
        TickEvent.SERVER_POST.register(WaitingRoom::onServerTick);
        LifecycleEvent.SERVER_STOPPING.register(WaitingRoom::onServerStopping);
    }

    private static ConditionConfig condition(MinecraftServer server) {
        return ServerConditionDispatcher.activeConfig(server).firstJoin();
    }

    private static WaitingRoomConfig config(MinecraftServer server) {
        ConditionConfig condition = condition(server);
        return condition == null || condition.resolvedPlaylist().isEmpty() ? null : condition.resolvedWaitingRoom();
    }

    public static boolean isMember(ServerPlayer player) {
        return MEMBERS.contains(player.getUUID());
    }

    // Spent means nobody else gets to see it: whoever arrives now just plays.
    private static boolean spent(MinecraftServer server) {
        return !condition(server).repeatableInSameSession() && ConditionalVideosWorldData.get(server).roomConsumed();
    }

    private static void onJoin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        WaitingRoomConfig room = config(server);
        if (room == null) {
            return;
        }
        String left = whyNotEntering(server, player, room);
        if (left != null) {
            DebugLog.server(DebugLog.Area.ROOM, "{} does not enter the waiting room: {}.", player.getScoreboardName(), left);
            return;
        }
        enter(server, player);
    }

    private static String whyNotEntering(MinecraftServer server, ServerPlayer player, WaitingRoomConfig room) {
        if (PlayerRoles.isHost(server, player)) {
            return "the host stays out unless they join";
        }
        if (spent(server)) {
            return "it has already finished";
        }
        ConditionConfig condition = condition(server);
        String excluded = PlayerExclusions.reason(player, condition);
        if (excluded != null) {
            return excluded;
        }
        if (!condition.repeatableInSameSession() && WatchedConditions.isWatched(player, KEY)) {
            return "they already watched it";
        }
        return isListed(server, player, room) || !hasList(room) ? null : "they are not listed";
    }

    private static boolean hasList(WaitingRoomConfig room) {
        return !room.resolvedPlayerNames().isEmpty() || !room.resolvedTeams().isEmpty();
    }

    private static boolean isListed(MinecraftServer server, ServerPlayer player, WaitingRoomConfig room) {
        for (String name : room.resolvedPlayerNames()) {
            if (name != null && name.equalsIgnoreCase(player.getScoreboardName())) {
                return true;
            }
        }
        String team = PlayerRoles.viewer(player).team();
        return team != null && room.resolvedTeams().contains(team);
    }

    // Everyone in the lists and every registered member of their teams, by lowercase name.
    private static Map<String, String> listedNames(MinecraftServer server, WaitingRoomConfig room) {
        Map<String, String> names = new LinkedHashMap<>();
        room.resolvedPlayerNames().forEach(name -> names.putIfAbsent(name.toLowerCase(Locale.ROOT), name));
        for (String teamName : room.resolvedTeams()) {
            PlayerTeam team = server.getScoreboard().getPlayerTeam(teamName);
            if (team != null) {
                team.getPlayers().forEach(name -> names.putIfAbsent(name.toLowerCase(Locale.ROOT), name));
            }
        }
        return names;
    }

    // Whoever is expected: those listed, otherwise the number asked for, or as many as the server fits.
    private static int required(MinecraftServer server, WaitingRoomConfig room) {
        if (hasList(room)) {
            return listedNames(server, room).size();
        }
        return room.resolvedRequiredPlayers() > 0 ? room.resolvedRequiredPlayers() : server.getPlayerList().getMaxPlayers();
    }

    // Those listed who are not in yet. Only the first few travel; the client shows two or three of them.
    private static List<String> missing(MinecraftServer server, WaitingRoomConfig room) {
        if (!hasList(room)) {
            return List.of();
        }
        Map<String, String> names = listedNames(server, room);
        for (UUID id : MEMBERS) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                names.remove(player.getScoreboardName().toLowerCase(Locale.ROOT));
            }
        }
        return names.values().stream().limit(WaitingRoomText.MISSING_SENT).toList();
    }

    // Someone brought in who is not on a list watches, but does not count.
    private static int current(MinecraftServer server, WaitingRoomConfig room) {
        if (!hasList(room)) {
            return MEMBERS.size();
        }
        int counted = 0;
        for (UUID id : MEMBERS) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && isListed(server, player, room)) {
                counted++;
            }
        }
        return counted;
    }

    private static void enter(MinecraftServer server, ServerPlayer player) {
        if (phase == Phase.FINISHED) {
            beginCycle();
        }
        PlayerExclusions.ensureDelivered(player, KEY);
        MEMBERS.add(player.getUUID());
        PlaybackSessions.enterRoom(player, condition(server));
        DebugLog.server(DebugLog.Area.ROOM, "{} entered the waiting room ({}).", player.getScoreboardName(), phase);
        if (phase == Phase.PLAYING) {
            markWatched(server, player);
        }
        evaluate(server);
        broadcastState(server);
        if (phase == Phase.PLAYING) {
            sendSnapshot(server, player);
        }
    }

    private static void beginCycle() {
        phase = Phase.OPEN;
        operatorsTold = false;
        READY_PLAYERS.clear();
        DURATIONS.clear();
        LIVE_ENTRIES.clear();
        pausedAt = 0L;
        DebugLog.server(DebugLog.Area.ROOM, "A new waiting room cycle opened.");
    }

    private static void leave(MinecraftServer server, ServerPlayer player, String reason) {
        if (!MEMBERS.remove(player.getUUID())) {
            return;
        }
        READY_PLAYERS.remove(player.getUUID());
        PlaybackSessions.leaveRoom(player, reason);
        PlaybackControlNetworking.sendRoomState(player, false, Phase.FINISHED, 0, 0, false, List.of());
        DebugLog.server(DebugLog.Area.ROOM, "{} left the waiting room: {}.", player.getScoreboardName(), reason);
        afterLeaving(server);
    }

    private static void onQuit(ServerPlayer player) {
        if (MEMBERS.remove(player.getUUID())) {
            READY_PLAYERS.remove(player.getUUID());
            DebugLog.server(DebugLog.Area.ROOM, "{} disconnected from the waiting room.", player.getScoreboardName());
            afterLeaving(player.getServer());
        }
    }

    private static void afterLeaving(MinecraftServer server) {
        if (phase == Phase.PLAYING && MEMBERS.isEmpty()) {
            finish(server, "everyone left");
            return;
        }
        if (phase == Phase.STARTING && MEMBERS.isEmpty()) {
            phase = Phase.OPEN;
        }
        evaluate(server);
        broadcastState(server);
    }

    private static boolean requirementMet(MinecraftServer server, WaitingRoomConfig room) {
        return !MEMBERS.isEmpty() && current(server, room) >= required(server, room);
    }

    // Moves between open and ready with who is in, and starts by itself in auto mode.
    private static void evaluate(MinecraftServer server) {
        WaitingRoomConfig room = config(server);
        if (room == null || (phase != Phase.OPEN && phase != Phase.READY)) {
            return;
        }
        if (!requirementMet(server, room)) {
            phase = Phase.OPEN;
            operatorsTold = false;
            return;
        }
        phase = Phase.READY;
        if (!room.startsByOperator()) {
            start(server);
        } else if (!operatorsTold) {
            operatorsTold = true;
            tellOperators(server);
        }
    }

    private static void tellOperators(MinecraftServer server) {
        Component button = Component.translatable("command.conditionalvideos.waitingroom.start_button")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, START_COMMAND)));
        Component message = Component.translatable("command.conditionalvideos.waitingroom.ready", button);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (PlayerRoles.isOperator(player)) {
                player.sendSystemMessage(message);
            }
        }
        DebugLog.server(DebugLog.Area.ROOM, "The waiting room is ready; asked the operators to start it.");
    }

    private static void start(MinecraftServer server) {
        phase = Phase.STARTING;
        barrierStart = System.currentTimeMillis();
        DebugLog.server(DebugLog.Area.ROOM, "The waiting room is starting: waiting for {} client(s) to preload, at most {} s.", MEMBERS.size(), BARRIER_MILLIS / 1000);
    }

    private static void play(MinecraftServer server) {
        phase = Phase.PLAYING;
        entry = 0;
        serial++;
        entryStart = System.currentTimeMillis();
        pausedAt = 0L;
        DebugLog.server(DebugLog.Area.ROOM, "The waiting room video starts for {} player(s); {} of them preloaded.", MEMBERS.size(), READY_PLAYERS.size());
        for (UUID id : MEMBERS) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                markWatched(server, player);
            }
        }
        broadcastState(server);
        broadcastSnapshot(server);
    }

    private static void markWatched(MinecraftServer server, ServerPlayer player) {
        if (!condition(server).repeatableInSameSession()) {
            ConditionalVideosWorldData.get(server).markWatched(player.getUUID(), player.getScoreboardName(), List.of(KEY));
        }
    }

    private static void finish(MinecraftServer server, String reason) {
        DebugLog.server(DebugLog.Area.ROOM, "The waiting room finished: {}.", reason);
        phase = Phase.FINISHED;
        for (UUID id : List.copyOf(MEMBERS)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                PlaybackSessions.leaveRoom(player, reason);
                PlaybackControlNetworking.sendRoomState(player, false, Phase.FINISHED, 0, 0, false, List.of());
            }
        }
        MEMBERS.clear();
        READY_PLAYERS.clear();
        if (!condition(server).repeatableInSameSession()) {
            ConditionalVideosWorldData.get(server).setRoomConsumed();
        }
    }

    private static void onServerTick(MinecraftServer server) {
        if (MEMBERS.isEmpty()) {
            return;
        }
        WaitingRoomConfig room = config(server);
        if (room == null) {
            DebugLog.server(DebugLog.Area.ROOM, "The waiting room was turned off in the file.");
            finish(server, "it was turned off");
            return;
        }
        boolean second = ++ticks % SECOND_TICKS == 0;
        if (second) {
            for (UUID id : MEMBERS) {
                PlaybackSessions.keepRoomAlive(id);
            }
            // Who is listed can change at any moment, in the file or in a team.
            if (phase != Phase.PLAYING) {
                evaluate(server);
                broadcastState(server);
            }
        }
        if (phase == Phase.STARTING) {
            boolean everyone = READY_PLAYERS.containsAll(MEMBERS);
            if (everyone || System.currentTimeMillis() - barrierStart >= BARRIER_MILLIS) {
                play(server);
            }
        } else if (phase == Phase.PLAYING) {
            advance(server);
            if (second && phase == Phase.PLAYING) {
                broadcastSnapshot(server);
            }
        }
    }

    // Where the session is in the current entry: absolute in the media, so startAt and nextAt mean what they say.
    private static long position(MinecraftServer server) {
        long running = (pausedAt != 0L ? pausedAt : System.currentTimeMillis()) - entryStart;
        return Math.round(entryOf(server).resolvedStartAt() * 1000d) + Math.max(0L, running);
    }

    private static VideoEntry entryOf(MinecraftServer server) {
        List<VideoEntry> playlist = condition(server).resolvedPlaylist();
        return playlist.get(Math.min(entry, playlist.size() - 1));
    }

    private static void advance(MinecraftServer server) {
        if (pausedAt != 0L) {
            return;
        }
        ConditionConfig condition = condition(server);
        List<VideoEntry> playlist = condition.resolvedPlaylist();
        if (entry >= playlist.size()) {
            finish(server, "the playlist changed");
            return;
        }
        VideoEntry current = playlist.get(entry);
        int next = current.resolvedVideoLoop() ? entry : entry + 1 < playlist.size() ? entry + 1 : condition.resolvedPlaylistLoop() ? 0 : -1;
        Float nextAt = current.resolvedNextAt();
        Long end = nextAt != null ? Long.valueOf(Math.round(nextAt * 1000d)) : DURATIONS.get(entry);
        if (end == null) {
            if (LIVE_ENTRIES.contains(entry) || position(server) - Math.round(current.resolvedStartAt() * 1000d) < UNKNOWN_DURATION_MILLIS) {
                return;
            }
            DebugLog.server(DebugLog.Area.ROOM, "No client reported how long entry {} lasts; moving on.", entry);
            end = 0L;
        }
        boolean fade = next >= 0 && next != entry && ConditionalVideosConfig.TRANSITION_FADE.equals(playlist.get(next).resolvedTransition());
        if (position(server) < end - (fade ? CROSSFADE_MILLIS : 0L)) {
            return;
        }
        if (next < 0) {
            finish(server, "the playlist ended");
            return;
        }
        entry = next;
        serial++;
        entryStart = System.currentTimeMillis();
        DebugLog.server(DebugLog.Area.ROOM, "The session moves on to entry {}.", entry);
        broadcastSnapshot(server);
    }

    private static void broadcastState(MinecraftServer server) {
        WaitingRoomConfig room = config(server);
        if (room == null) {
            return;
        }
        int current = current(server, room);
        int required = required(server, room);
        List<String> missing = missing(server, room);
        String shown = current + "/" + required + " " + missing;
        if (!shown.equals(lastShown)) {
            lastShown = shown;
            DebugLog.server(DebugLog.Area.ROOM, "The waiting room shows {}.", shown);
        }
        for (UUID id : MEMBERS) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                PlaybackControlNetworking.sendRoomState(player, true, phase, current, required, PlayerRoles.isOperator(player), missing);
            }
        }
    }

    private static void broadcastSnapshot(MinecraftServer server) {
        for (UUID id : MEMBERS) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                sendSnapshot(server, player);
            }
        }
    }

    private static void sendSnapshot(MinecraftServer server, ServerPlayer player) {
        Long duration = DURATIONS.get(entry);
        PlaybackControlNetworking.sendRoomSnapshot(player, serial, entry, pausedAt != 0L, position(server), duration == null ? 0L : duration, LIVE_ENTRIES.contains(entry));
    }

    public static void onReady(ServerPlayer player) {
        if (isMember(player) && READY_PLAYERS.add(player.getUUID())) {
            DebugLog.server(DebugLog.Area.ROOM, "{} has the video ready ({}/{}).", player.getScoreboardName(), READY_PLAYERS.size(), MEMBERS.size());
        }
    }

    // The first report of an entry sets how long it lasts for everyone.
    public static void onDuration(ServerPlayer player, int reportedEntry, long millis, boolean live) {
        if (!isMember(player) || phase != Phase.PLAYING || reportedEntry != entry) {
            return;
        }
        if (live && LIVE_ENTRIES.add(reportedEntry)) {
            DebugLog.server(DebugLog.Area.ROOM, "{} reports entry {} is live.", player.getScoreboardName(), reportedEntry);
            broadcastSnapshot(player.getServer());
        } else if (!live && millis > 0L && !DURATIONS.containsKey(reportedEntry)) {
            DURATIONS.put(reportedEntry, millis);
            DebugLog.server(DebugLog.Area.ROOM, "{} reports entry {} lasts {} ms.", player.getScoreboardName(), reportedEntry, millis);
            broadcastSnapshot(player.getServer());
        }
    }

    // Before the video only allowLeave lets someone out; once it plays, skipping unhooks that player.
    public static void onLeaveRequest(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        WaitingRoomConfig room = config(server);
        if (room != null && isMember(player) && (phase == Phase.PLAYING || room.resolvedAllowLeave())) {
            leave(server, player, phase == Phase.PLAYING ? "they skipped the video" : "they left");
        }
    }

    public static void onStartRequest(ServerPlayer player) {
        if (PlayerRoles.isOperator(player) && isMember(player)) {
            requestStart(player.getServer(), true);
        }
    }

    // Force starts it with whoever is in, whether or not its requirement is met.
    public static StartResult requestStart(MinecraftServer server, boolean force) {
        WaitingRoomConfig room = config(server);
        if (room == null) {
            return StartResult.OFF;
        }
        if (spent(server)) {
            return StartResult.FINISHED;
        }
        if (phase == Phase.STARTING || phase == Phase.PLAYING) {
            return StartResult.ALREADY_STARTED;
        }
        if (MEMBERS.isEmpty()) {
            return StartResult.EMPTY;
        }
        if (!force && !requirementMet(server, room)) {
            return StartResult.NOT_READY;
        }
        DebugLog.server(DebugLog.Area.ROOM, "The waiting room was started from {}{}.", phase, force ? " by force" : "");
        start(server);
        broadcastState(server);
        return StartResult.STARTED;
    }

    // Who is in out of who it waits for, or null without a room.
    public static String progress(MinecraftServer server) {
        WaitingRoomConfig room = config(server);
        return room == null ? null : current(server, room) + "/" + required(server, room);
    }

    // Commands: an operator may bring anyone in, past any list or exclusion but the file's excludeOps.
    public static AddResult add(MinecraftServer server, ServerPlayer player) {
        if (config(server) == null) {
            return AddResult.OFF;
        }
        if (spent(server)) {
            return AddResult.FINISHED;
        }
        if (isMember(player)) {
            return AddResult.ALREADY_IN;
        }
        if (condition(server).resolvedExcludeOps() && PlayerRoles.isOperator(player)) {
            return AddResult.LEFT_OUT;
        }
        enter(server, player);
        return AddResult.ENTERED;
    }

    // Those written on the file's list, whether or not the room is on.
    public static List<String> writtenNames(MinecraftServer server) {
        WaitingRoomConfig room = condition(server).waitingRoom();
        return room == null ? List.of() : room.resolvedPlayerNames();
    }

    // On the list, or in one of its teams.
    public static boolean isExpected(MinecraftServer server, String name) {
        WaitingRoomConfig room = condition(server).waitingRoom();
        return room != null && listedNames(server, room).containsKey(name.toLowerCase(Locale.ROOT));
    }

    // As if they had disconnected: the room still waits for them, and join brings them back.
    public static boolean leaveByCommand(ServerPlayer player) {
        if (!isMember(player)) {
            return false;
        }
        leave(player.getServer(), player, "they left by command");
        return true;
    }

    public static boolean remove(MinecraftServer server, ServerPlayer player) {
        if (!isMember(player)) {
            return false;
        }
        leave(server, player, "an operator took them out");
        return true;
    }

    // Stop and pause orders aimed at someone watching the session act on the whole session.
    public static boolean handleStop(ServerPlayer player) {
        if (!isMember(player) || phase != Phase.PLAYING) {
            return false;
        }
        finish(player.getServer(), "a stop order");
        return true;
    }

    public static boolean handlePause(ServerPlayer player) {
        if (!isMember(player) || phase != Phase.PLAYING) {
            return false;
        }
        if (pausedAt == 0L) {
            pausedAt = System.currentTimeMillis();
        } else {
            entryStart += System.currentTimeMillis() - pausedAt;
            pausedAt = 0L;
        }
        DebugLog.server(DebugLog.Area.ROOM, "The session was {} by an order.", pausedAt != 0L ? "paused" : "resumed");
        broadcastSnapshot(player.getServer());
        return true;
    }

    // A session still playing when the server stops counts as finished, so a restart does not open it again.
    public static void onServerStopping(MinecraftServer server) {
        if (phase == Phase.PLAYING && !condition(server).repeatableInSameSession()) {
            ConditionalVideosWorldData.get(server).setRoomConsumed();
        }
        reset();
    }

    private static void reset() {
        MEMBERS.clear();
        READY_PLAYERS.clear();
        DURATIONS.clear();
        LIVE_ENTRIES.clear();
        phase = Phase.OPEN;
        operatorsTold = false;
        pausedAt = 0L;
    }

}
