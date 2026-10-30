package org.mateof24.conditionalvideos.world;

import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// The sync option of play: a player joins whoever is watching the condition at the point they are. One of them is
// asked for their position; with nobody watching, or no answer in time, the video starts from the beginning.
public final class PlaybackSync {
    private static final long ANSWER_MILLIS = 2000L;

    private static final List<Pending> PENDING = new ArrayList<>();

    private record Pending(UUID target, UUID reference, String key, long askedAt) {
    }

    private PlaybackSync() {
    }

    public static void init() {
        PlayerEvent.PLAYER_QUIT.register(player -> PENDING.removeIf(pending -> pending.target().equals(player.getUUID())));
        TickEvent.SERVER_POST.register(PlaybackSync::onServerTick);
    }

    public static void send(MinecraftServer server, ServerPlayer target, String key) {
        ServerPlayer reference = PlaybackSessions.otherWatcher(server, key, target.getUUID());
        if (reference == null) {
            DebugLog.server(DebugLog.Area.NETWORK, "Nobody is watching '{}'; {} starts it from the beginning.", key, target.getScoreboardName());
            PlaybackControlNetworking.sendPlay(target, key);
            return;
        }
        PENDING.add(new Pending(target.getUUID(), reference.getUUID(), key, System.currentTimeMillis()));
        DebugLog.server(DebugLog.Area.NETWORK, "{} joins '{}' at the point of {}.", target.getScoreboardName(), key, reference.getScoreboardName());
        PlaybackControlNetworking.sendPositionRequest(reference, key);
    }

    public static boolean isPending(ServerPlayer player) {
        return PENDING.stream().anyMatch(pending -> pending.target().equals(player.getUUID()));
    }

    public static void onPosition(MinecraftServer server, ServerPlayer reference, String key, boolean known, int entry, double seconds) {
        PENDING.removeIf(pending -> {
            if (!pending.reference().equals(reference.getUUID()) || !pending.key().equals(key)) {
                return false;
            }
            ServerPlayer target = server.getPlayerList().getPlayer(pending.target());
            if (target != null && known) {
                PlaybackControlNetworking.sendPlayAt(target, key, entry, seconds);
            } else if (target != null) {
                PlaybackControlNetworking.sendPlay(target, key);
            }
            return true;
        });
    }

    private static void onServerTick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        PENDING.removeIf(pending -> {
            if (now - pending.askedAt() < ANSWER_MILLIS) {
                return false;
            }
            ServerPlayer target = server.getPlayerList().getPlayer(pending.target());
            if (target != null) {
                DebugLog.server(DebugLog.Area.NETWORK, "No position for '{}' in time; {} starts it from the beginning.", pending.key(), target.getScoreboardName());
                PlaybackControlNetworking.sendPlay(target, pending.key());
            }
            return true;
        });
    }

    public static void reset() {
        PENDING.clear();
    }
}
