package org.mateof24.conditionalvideos.world;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.server.WorldPositionConditions;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.world.ConditionalVideosWorldData.Protection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// What the server does while a player watches a video. The client reports when the loading screen or
// the video appears, a pulse while it lasts and when the playlist ends; the server accepts it only when
// the condition exists here and its trigger is plausible (ordered by the server, firstJoin right after
// joining, a recent death or kill, the current dimension, a completed advancement), so a modified client
// cannot protect itself at will. The host of a singleplayer or LAN world is always trusted.
// Protection never touches the game mode: only abilities.invulnerable is raised, the previous value is
// kept in the world data so a disconnect or a crash restores it, and it is raised again after a respawn
// or a game mode change. A protected player also picks nothing up and is not pushed.
// The world freezes only while every connected player is in a session whose condition has freezeWorld.
public final class PlaybackSessions {
    public enum Phase { LOADING, STARTED, ENDED, ALIVE, DELAYED }

    private static final long SIGNAL_TIMEOUT_MILLIS = 30_000L;
    private static final long FIRST_JOIN_WINDOW_MILLIS = 5 * 60_000L;
    private static final long RECENT_EVENT_MILLIS = 60_000L;
    private static final int CHECK_INTERVAL_TICKS = 20;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, PlayerEvents> EVENTS = new HashMap<>();
    // The local player's UUID while its own client plays a protected video (client side only).
    private static volatile UUID clientImmune;
    private static int ticks;

    private PlaybackSessions() {
    }

    public static void init() {
        LifecycleEvent.SERVER_STARTING.register(PlaybackSessions::reset);
        LifecycleEvent.SERVER_STOPPING.register(PlaybackSessions::onServerStopping);
        PlayerEvent.PLAYER_JOIN.register(PlaybackSessions::onJoin);
        PlayerEvent.PLAYER_QUIT.register(PlaybackSessions::onQuit);
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            onDeath(entity, source);
            return EventResult.pass();
        });
        TickEvent.SERVER_POST.register(PlaybackSessions::onServerTick);
    }

    // True while this player must not pick things up nor be pushed.
    public static boolean isImmune(Entity entity) {
        if (!(entity instanceof Player)) {
            return false;
        }
        if (entity.level().isClientSide()) {
            return entity.getUUID().equals(clientImmune);
        }
        Session session = SESSIONS.get(entity.getUUID());
        return session != null && session.protect;
    }

    public static void setClientImmune(UUID player) {
        clientImmune = player;
    }

    // Every play order the server sends allows one session for that condition.
    public static void recordOrder(ServerPlayer player, String key) {
        events(player.getUUID()).orders.merge(key, 1, Integer::sum);
    }

    // natural is the client's word that its own detection triggered the playback; server orders are known here.
    public static void onState(ServerPlayer player, String key, Phase phase, boolean reportedNatural) {
        UUID id = player.getUUID();
        Session session = SESSIONS.get(id);
        boolean current = session != null && session.key.equals(key);
        boolean natural = reportedNatural || WatchedConditions.wasOrdered(player, key);
        switch (phase) {
            case DELAYED -> {
                events(id).waitingSince.putIfAbsent(key, System.currentTimeMillis());
                if (natural) {
                    markInProgress(player, key);
                }
            }
            case ALIVE -> {
                if (current) {
                    session.lastSignal = System.currentTimeMillis();
                }
            }
            case ENDED -> {
                if (current) {
                    end(player, id, "the playlist ended", null);
                }
                events(id).waitingSince.remove(key);
                finishInProgress(player, key);
            }
            case LOADING, STARTED -> {
                if (natural) {
                    markInProgress(player, key);
                }
                if (phase == Phase.STARTED) {
                    events(id).started.add(key);
                    WatchedConditions.onStarted(player, key, natural);
                }
                if (current) {
                    session.lastSignal = System.currentTimeMillis();
                } else {
                    begin(player, key);
                }
                events(id).waitingSince.remove(key);
            }
        }
    }

    // A natural playback stays noted in the world data while it waits, loads or plays, so a disconnect or a
    // crash in between can be recognised when the player returns.
    public static void markInProgress(ServerPlayer player, String key) {
        MinecraftServer server = player.getServer();
        if (ConditionRegistry.resolve(ServerConditionDispatcher.activeConfig(server), key) != null) {
            ConditionalVideosWorldData.get(server).setInProgress(player.getUUID(), player.getScoreboardName(), key, true);
        }
    }

    public static void clearInProgress(ServerPlayer player, String key) {
        ConditionalVideosWorldData.get(player.getServer()).setInProgress(player.getUUID(), player.getScoreboardName(), key, false);
    }

    // A replayOnReconnect video that played to its end counts as watched only now.
    private static void finishInProgress(ServerPlayer player, String key) {
        MinecraftServer server = player.getServer();
        ConditionalVideosWorldData data = ConditionalVideosWorldData.get(server);
        if (!data.inProgress(player.getUUID()).contains(key)) {
            return;
        }
        data.setInProgress(player.getUUID(), player.getScoreboardName(), key, false);
        ConditionConfig condition = ConditionRegistry.resolve(ServerConditionDispatcher.activeConfig(server), key);
        if (condition != null && !condition.repeatableInSameSession() && condition.resolvedReplayOnReconnect() && events(player.getUUID()).started.contains(key)) {
            data.markWatched(player.getUUID(), player.getScoreboardName(), List.of(key));
            DebugLog.server(DebugLog.Area.WORLD, "{} finished '{}'; it will not play again for them in this world.", player.getScoreboardName(), key);
        }
    }

    // What is still noted when a player arrives was interrupted. With replayOnReconnect it plays again from the
    // start (firstJoin through its own join flow, the rest by a server order); otherwise it stays watched.
    public static void resolveInterrupted(ServerPlayer player) {
        PlayerEvents events = events(player.getUUID());
        if (events.interruptedResolved) {
            return;
        }
        events.interruptedResolved = true;
        MinecraftServer server = player.getServer();
        ConditionalVideosWorldData data = ConditionalVideosWorldData.get(server);
        for (String key : List.copyOf(data.inProgress(player.getUUID()))) {
            data.setInProgress(player.getUUID(), player.getScoreboardName(), key, false);
            ConditionConfig condition = ConditionRegistry.resolve(ServerConditionDispatcher.activeConfig(server), key);
            if (condition == null || condition.resolvedPlaylist().isEmpty() || PlayerExclusions.reason(player, condition) != null) {
                continue;
            }
            if (condition.resolvedReplayOnReconnect()) {
                if (!ConditionRegistry.KEY_FIRST_JOIN.equals(key)) {
                    events.replays.add(key);
                }
                DebugLog.server(DebugLog.Area.WORLD, "{} was interrupted during '{}'; it plays again.", player.getScoreboardName(), key);
            } else if (!condition.repeatableInSameSession()) {
                data.markWatched(player.getUUID(), player.getScoreboardName(), List.of(key));
                DebugLog.server(DebugLog.Area.WORLD, "{} was interrupted during '{}'; it stays watched.", player.getScoreboardName(), key);
            }
        }
    }

    public static List<String> describe(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        SESSIONS.forEach((id, session) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            lines.add((player == null ? id.toString() : player.getScoreboardName()) + " '" + session.key + "'"
                    + (session.protect ? " protected" : "") + (session.freeze ? " freezeWorld" : ""));
        });
        return lines;
    }

    private static void begin(ServerPlayer player, String key) {
        MinecraftServer server = player.getServer();
        ConditionConfig condition = ConditionRegistry.resolve(ServerConditionDispatcher.activeConfig(server), key);
        String rejection = rejection(server, player, key, condition);
        if (rejection != null) {
            DebugLog.server(DebugLog.Area.PROTECT, "Refused the playback of '{}' reported by {}: {}.", key,
                    player.getScoreboardName(), rejection);
            return;
        }
        if (SESSIONS.containsKey(player.getUUID())) {
            end(player, player.getUUID(), "replaced by '" + key + "'", null);
        }
        Session session = new Session(key, condition.resolvedProtectPlayer(), condition.resolvedFreezeWorld());
        SESSIONS.put(player.getUUID(), session);
        if (session.protect) {
            ConditionalVideosWorldData data = ConditionalVideosWorldData.get(server);
            if (data.protection(player.getUUID()) == null) {
                data.setProtection(player.getUUID(), player.getScoreboardName(), new Protection(key,
                        player.getAbilities().invulnerable, player.gameMode.getGameModeForPlayer().getName()));
            }
            setInvulnerable(player, true);
        }
        DebugLog.server(DebugLog.Area.PROTECT, "{} is watching '{}' ({}, freezeWorld {}).", player.getScoreboardName(), key,
                session.protect ? "protected" : "protectPlayer off", session.freeze);
        updateFreeze(server, null);
    }

    // leaving is a player who is disconnecting and must not count for the freeze any more.
    private static void end(ServerPlayer player, UUID id, String reason, UUID leaving) {
        Session session = SESSIONS.remove(id);
        if (session == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (session.protect) {
            release(player, ConditionalVideosWorldData.get(server));
        }
        DebugLog.server(DebugLog.Area.PROTECT, "{} stopped watching '{}': {}{}.", player.getScoreboardName(), session.key, reason,
                session.protect ? "; protection removed" : "");
        updateFreeze(server, leaving);
    }

    // Puts invulnerability back as it was, unless an admin changed the game mode meanwhile: then the new
    // mode decides (creative and spectator are invulnerable).
    private static void release(ServerPlayer player, ConditionalVideosWorldData data) {
        Protection protection = data.protection(player.getUUID());
        if (protection == null) {
            return;
        }
        GameType mode = player.gameMode.getGameModeForPlayer();
        boolean restored = mode.getName().equals(protection.gameMode())
                ? protection.invulnerable()
                : mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
        setInvulnerable(player, restored);
        data.setProtection(player.getUUID(), player.getScoreboardName(), null);
    }

    private static String rejection(MinecraftServer server, ServerPlayer player, String key, ConditionConfig condition) {
        if (condition == null) {
            return "it is not a condition of this server";
        }
        if (condition.resolvedPlaylist().isEmpty()) {
            return "it has no videos here";
        }
        PlayerEvents events = events(player.getUUID());
        Integer orders = events.orders.get(key);
        if (orders != null) {
            if (orders <= 1) {
                events.orders.remove(key);
            } else {
                events.orders.put(key, orders - 1);
            }
            return null;
        }
        String excluded = PlayerExclusions.reason(player, condition);
        if (excluded != null) {
            return "the player is left out of it: " + excluded;
        }
        if (PlayerRoles.isHost(server, player)) {
            return null;
        }
        long now = System.currentTimeMillis();
        long delayMillis = condition.resolvedDelayTicks() * 50L;
        // A video that waited or prepared in silence is judged by when it fired, within a limit.
        Long waitingSince = events.waitingSince.get(key);
        if (waitingSince != null && now - waitingSince <= delayMillis + FIRST_JOIN_WINDOW_MILLIS) {
            now = waitingSince;
        }
        int slash = key.indexOf('/');
        String type = slash < 0 ? key : key.substring(0, slash);
        String id = slash < 0 ? "" : key.substring(slash + 1);
        switch (type) {
            case ConditionRegistry.KEY_FIRST_JOIN -> {
                if (now - events.joinedAt > FIRST_JOIN_WINDOW_MILLIS + delayMillis) {
                    return "firstJoin is only accepted right after joining";
                }
                if (events.firstJoinAccepted) {
                    return "firstJoin was already accepted during this connection";
                }
                events.firstJoinAccepted = true;
                return null;
            }
            case ConditionRegistry.KEY_PLAYER_DEATH, ConditionRegistry.TYPE_DEATH_BY_ENTITY -> {
                if (now - events.diedAt > RECENT_EVENT_MILLIS + delayMillis) {
                    return "the player did not die recently";
                }
                events.diedAt = Long.MIN_VALUE / 2;
                return null;
            }
            case ConditionRegistry.TYPE_ENTITY_KILLED -> {
                Long killedAt = events.kills.remove(id);
                return killedAt != null && now - killedAt <= RECENT_EVENT_MILLIS + delayMillis ? null : "the player did not kill a " + id + " recently";
            }
            case ConditionRegistry.TYPE_DIMENSION -> {
                Long seenAt = events.dimensionsSeen.get(id);
                if (!dimensionOf(player).equals(id) && (seenAt == null || now - seenAt > RECENT_EVENT_MILLIS + delayMillis)) {
                    return "the player is not in " + id;
                }
                if (id.equals(events.dimensionAccepted)) {
                    return "it was already accepted since the player entered " + id;
                }
                events.dimensionAccepted = id;
                return null;
            }
            case ConditionRegistry.TYPE_ADVANCEMENT -> {
                if (!hasAdvancement(server, player, id)) {
                    return "the player has not completed " + id;
                }
                return events.advancementsAccepted.add(id) ? null : "it was already accepted during this connection";
            }
            default -> {
                return "only the server can start it";
            }
        }
    }

    private static void onJoin(ServerPlayer player) {
        PlayerEvents events = events(player.getUUID());
        events.joinedAt = System.currentTimeMillis();
        events.dimension = dimensionOf(player);
        events.dimensionsSeen.put(events.dimension, events.joinedAt);
        MinecraftServer server = player.getServer();
        ConditionalVideosWorldData data = ConditionalVideosWorldData.get(server);
        Protection left = data.protection(player.getUUID());
        if (left != null && !SESSIONS.containsKey(player.getUUID())) {
            release(player, data);
            DebugLog.server(DebugLog.Area.PROTECT, "{} still had the protection of '{}' from an interrupted session; removed it.",
                    player.getScoreboardName(), left.key());
        }
        updateFreeze(server, null);
    }

    private static void onQuit(ServerPlayer player) {
        end(player, player.getUUID(), "disconnected", player.getUUID());
        EVENTS.remove(player.getUUID());
        updateFreeze(player.getServer(), player.getUUID());
    }

    private static void onDeath(LivingEntity entity, DamageSource source) {
        if (entity.level().isClientSide()) {
            return;
        }
        if (entity instanceof ServerPlayer player) {
            events(player.getUUID()).diedAt = System.currentTimeMillis();
        }
        Entity killer = source.getEntity();
        if (!(killer instanceof ServerPlayer)) {
            killer = entity.getKillCredit();
        }
        if (killer instanceof ServerPlayer player && player != entity) {
            events(player.getUUID()).kills.put(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), System.currentTimeMillis());
        }
    }

    // Keeps protected players invulnerable every tick (a respawn or a game mode change resets it), and once
    // a second drops sessions whose client went silent, tracks dimension changes and checks the freeze.
    private static void onServerTick(MinecraftServer server) {
        if (SESSIONS.isEmpty() && EVENTS.isEmpty()) {
            return;
        }
        EVENTS.forEach((id, events) -> {
            ServerPlayer player = events.replays.isEmpty() ? null : server.getPlayerList().getPlayer(id);
            if (player != null) {
                for (String key : events.replays) {
                    if (WatchedConditions.order(player, key)) {
                        PlaybackControlNetworking.sendPlay(player, key);
                    }
                }
                events.replays.clear();
            }
        });
        SESSIONS.forEach((id, session) -> {
            ServerPlayer player = session.protect ? server.getPlayerList().getPlayer(id) : null;
            if (player != null && !player.getAbilities().invulnerable) {
                setInvulnerable(player, true);
                DebugLog.server(DebugLog.Area.PROTECT, "Protection of {} raised again (respawn or game mode change).",
                        player.getScoreboardName());
            }
        });
        if (++ticks % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (UUID id : List.copyOf(SESSIONS.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && now - SESSIONS.get(id).lastSignal > SIGNAL_TIMEOUT_MILLIS) {
                end(player, id, "no signal from the client for " + SIGNAL_TIMEOUT_MILLIS / 1000 + " s", null);
            }
        }
        EVENTS.forEach((id, events) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            String dimension = player == null ? events.dimension : dimensionOf(player);
            events.dimensionsSeen.put(dimension, now);
            if (!dimension.equals(events.dimension)) {
                events.dimension = dimension;
                events.dimensionAccepted = null;
            }
        });
        updateFreeze(server, null);
    }

    private static void updateFreeze(MinecraftServer server, UUID leaving) {
        int watching = 0;
        boolean everyone = true;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(leaving)) {
                continue;
            }
            Session session = SESSIONS.get(player.getUUID());
            if (session == null || !session.freeze) {
                everyone = false;
                break;
            }
            watching++;
        }
        WorldFreeze.set(server, everyone && watching > 0);
    }

    // Players are still online here, so their protection is removed before they are saved.
    private static void onServerStopping(MinecraftServer server) {
        for (UUID id : List.copyOf(SESSIONS.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                end(player, id, "the server is stopping", id);
            }
        }
        reset(server);
    }

    private static void reset(MinecraftServer server) {
        SESSIONS.clear();
        EVENTS.clear();
        ServerConditionDispatcher.clearDelayed();
        WorldPositionConditions.reset();
        PlayerExclusions.reset();
        ticks = 0;
        WorldFreeze.reset(server);
    }

    private static void setInvulnerable(ServerPlayer player, boolean value) {
        Abilities abilities = player.getAbilities();
        if (abilities.invulnerable != value) {
            abilities.invulnerable = value;
            player.onUpdateAbilities();
        }
    }

    private static PlayerEvents events(UUID player) {
        return EVENTS.computeIfAbsent(player, id -> new PlayerEvents());
    }

    public static String dimensionOf(ServerPlayer player) {
        return player.level().dimension().location().toString();
    }

    private static boolean hasAdvancement(MinecraftServer server, ServerPlayer player, String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        AdvancementHolder advancement = location == null ? null : server.getAdvancements().get(location);
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static final class Session {
        private final String key;
        private final boolean protect;
        private final boolean freeze;
        private long lastSignal = System.currentTimeMillis();

        private Session(String key, boolean protect, boolean freeze) {
            this.key = key;
            this.protect = protect;
            this.freeze = freeze;
        }
    }

    // What this connection did that can justify a session: orders received, when it joined and died, and
    // the kills, dimension and advancements already used.
    private static final class PlayerEvents {
        private final Map<String, Integer> orders = new HashMap<>();
        private final Map<String, Long> kills = new HashMap<>();
        private final Set<String> advancementsAccepted = new HashSet<>();
        private final Map<String, Long> dimensionsSeen = new HashMap<>();
        private final Map<String, Long> waitingSince = new HashMap<>();
        private final Set<String> started = new HashSet<>();
        private final List<String> replays = new ArrayList<>();
        private boolean interruptedResolved;
        private long joinedAt = Long.MIN_VALUE / 2;
        private long diedAt = Long.MIN_VALUE / 2;
        private boolean firstJoinAccepted;
        private String dimension = "";
        private String dimensionAccepted;
    }
}
