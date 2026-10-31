package org.mateof24.conditionalvideos.runtime;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.shared.ConditionVideoPlayer;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.VideoEntry;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.WaitingRoomConfig;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.video.SilentVideoPreparation;
import org.mateof24.conditionalvideos.video.VideoAudioState;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;
import org.mateof24.conditionalvideos.video.WaitingRoomOverlay;
import org.mateof24.conditionalvideos.video.WaitingRoomScreen;
import org.mateof24.conditionalvideos.video.backend.WaterMediaVideoBackend;
import org.mateof24.conditionalvideos.world.PlaybackSessions;
import org.mateof24.conditionalvideos.world.WaitingRoom;
import org.watermedia.api.media.MediaPlayer;
import org.watermedia.api.media.Sync;

import java.net.URI;
import java.util.List;
import java.util.function.Function;

// This client's part of the waiting room: shows it, keeps other screens away, gets the first video ready and, once
// the server's session plays, feeds the video screen the snapshots it receives.
public final class WaitingRoomClient {
    private static final String CONDITION = "firstJoin";

    private static boolean active;
    private static WaitingRoom.Phase phase = WaitingRoom.Phase.OPEN;
    private static int current;
    private static int required;
    private static List<String> missing = List.of();
    private static boolean operator;
    private static boolean pauseOpen;

    private static WaitingRoomOverlay overlay;
    private static SilentVideoPreparation preparation;
    private static boolean preparationDone;
    private static boolean readySent;

    private static VideoPlaybackScreen videoScreen;
    private static Snapshot lastSnapshot;
    private static int revision;
    private static int reportedEntry = -1;
    private static boolean closingForRoom;
    // Skipped out of the video and waiting for the server to confirm: nothing opens again meanwhile.
    private static boolean detached;

    private record Snapshot(int serial, int entry, boolean paused, long time, long duration, boolean live) {
    }

    private WaitingRoomClient() {
    }

    public static void init() {
        ClientGuiEvent.RENDER_PRE.register((screen, graphics, mouseX, mouseY, delta) -> {
            if (canDraw() && !(screen instanceof WaitingRoomScreen) && !(screen instanceof VideoPlaybackScreen)) {
                Minecraft minecraft = Minecraft.getInstance();
                overlay.render(graphics, minecraft.font, screen.width, screen.height, current, required, missing);
            }
            return EventResult.pass();
        });
        // The moment between one screen closing and the room's opening again, with no screen at all.
        ClientGuiEvent.RENDER_HUD.register((graphics, delta) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (canDraw() && minecraft.gui.screen() == null) {
                overlay.render(graphics, minecraft.font, graphics.guiWidth(), graphics.guiHeight(), current, required, missing);
                if (videoScreen == null && lastSnapshot == null) {
                    keepRoomOnScreen(minecraft);
                }
            }
        });
    }

    public static boolean isActive() {
        return active;
    }

    // Only while connected: leaving keeps other screens up for a while before this client hears about it.
    public static boolean canDraw() {
        Minecraft minecraft = Minecraft.getInstance();
        var connection = minecraft.getConnection();
        return active && !detached && overlay != null && minecraft.level != null && connection != null && connection.getConnection().isConnected();
    }

    public static List<String> missing() {
        return missing;
    }

    public static WaitingRoomOverlay overlay() {
        return overlay;
    }

    public static int current() {
        return current;
    }

    public static int required() {
        return required;
    }

    private static WaitingRoomConfig config() {
        ConditionConfig condition = ActiveConfigResolver.resolve(Minecraft.getInstance()).firstJoin();
        return condition == null ? null : condition.resolvedWaitingRoom();
    }

    public static boolean canLeave() {
        WaitingRoomConfig room = config();
        return room != null && room.resolvedAllowLeave();
    }

    public static boolean canStart() {
        return operator && (phase == WaitingRoom.Phase.OPEN || phase == WaitingRoom.Phase.READY);
    }

    public static boolean canChat() {
        WaitingRoomConfig room = config();
        return operator || (room != null && room.resolvedAllowChat());
    }

    public static void onState(boolean inRoom, WaitingRoom.Phase newPhase, int newCurrent, int newRequired, boolean isOperator, List<String> newMissing) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!inRoom) {
            if (active) {
                DebugLog.client(DebugLog.Area.ROOM, "Out of the waiting room.");
                leave(minecraft);
            }
            return;
        }
        if (!active) {
            enter(minecraft);
        }
        if (newPhase != phase) {
            DebugLog.client(DebugLog.Area.ROOM, "The waiting room is now {} ({}/{}).", newPhase, newCurrent, newRequired);
        }
        phase = newPhase;
        current = newCurrent;
        required = newRequired;
        operator = isOperator;
        missing = newMissing;
    }

    private static void enter(Minecraft minecraft) {
        active = true;
        ConditionConfig condition = ActiveConfigResolver.resolve(minecraft).firstJoin();
        WaitingRoomConfig room = condition == null ? null : condition.resolvedWaitingRoom();
        DebugLog.client(DebugLog.Area.ROOM, "Entered the waiting room.");
        VideoAudioState.setAllowGameSounds(ActiveConfigResolver.effectiveAllowGameSounds(minecraft));
        VideoAudioState.setLoadingActive(true);
        if (minecraft.player != null && condition != null && condition.resolvedProtectPlayer()) {
            PlaybackSessions.setClientImmune(minecraft.player.getUUID());
        }
        if (room != null) {
            Function<VideoEntry, URI> sources = ConditionVideoPlayer.sourceResolver(minecraft, "waiting room");
            overlay = new WaitingRoomOverlay(room, path -> ConditionVideoPlayer.backgroundSource(minecraft, path), ConditionVideoPlayer.downloadTimeoutTicks());
            preparation = new SilentVideoPreparation(condition.resolvedPlaylist(), sources,
                    path -> ConditionVideoPlayer.isDownloadPending(minecraft, path), ConditionVideoPlayer.downloadTimeoutTicks(), null);
        }
        preparationDone = false;
        readySent = false;
        detached = false;
        reportedEntry = -1;
        lastSnapshot = null;
    }

    // Nothing is sent from here: the server already knows, or this client is gone.
    private static void leave(Minecraft minecraft) {
        active = false;
        pauseOpen = false;
        VideoAudioState.setLoadingActive(false);
        PlaybackSessions.setClientImmune(null);
        if (preparation != null) {
            preparation.cancel();
            preparation = null;
        }
        if (overlay != null) {
            overlay.close();
            overlay = null;
        }
        if (videoScreen != null) {
            closingForRoom = true;
            videoScreen.closeForRoom();
            closingForRoom = false;
            videoScreen = null;
        }
        if (minecraft.gui.screen() instanceof WaitingRoomScreen) {
            minecraft.gui.setScreen(null);
        }
        lastSnapshot = null;
        ConditionVideoPlayer.playNextInQueue(minecraft);
    }

    public static void onDisconnected() {
        if (active) {
            leave(Minecraft.getInstance());
        }
    }

    public static void tick(Minecraft minecraft) {
        if (!active || detached) {
            return;
        }
        if (overlay != null) {
            WaitingRoomConfig room = config();
            if (room != null) {
                overlay.update(room);
            }
            overlay.tick();
        }
        tickPreparation();
        if (lastSnapshot != null && videoScreen == null) {
            openVideo(minecraft);
        }
        if (videoScreen != null) {
            reportDuration();
            return;
        }
        keepRoomOnScreen(minecraft);
    }

    private static void tickPreparation() {
        if (preparation == null || preparationDone) {
            return;
        }
        SilentVideoPreparation.Result result = preparation.tick(true);
        if (result == SilentVideoPreparation.Result.FAILED) {
            preparationDone = true;
            ConditionalVideos.LOGGER.warn("The waiting room could not get its video ready on this client.");
        } else if (preparation.isHeldReady()) {
            preparationDone = true;
        }
        if (preparationDone && !readySent) {
            readySent = true;
            PlaybackControlNetworking.sendRoomReady();
            DebugLog.client(DebugLog.Area.ROOM, "Told the server the video is ready.");
        }
    }

    // Only the pause menu with what opens from it, the chat when allowed, and the death screen can cover the room.
    private static void keepRoomOnScreen(Minecraft minecraft) {
        Screen screen = minecraft.gui.screen();
        if (screen == null) {
            pauseOpen = false;
            minecraft.gui.setScreen(new WaitingRoomScreen());
            return;
        }
        if (screen instanceof WaitingRoomScreen || screen instanceof DeathScreen) {
            pauseOpen = false;
            return;
        }
        if (screen instanceof PauseScreen) {
            pauseOpen = true;
            return;
        }
        if (pauseOpen || (screen instanceof ChatScreen && canChat())) {
            return;
        }
        DebugLog.client(DebugLog.Area.ROOM, "Kept {} out of the waiting room.", screen.getClass().getSimpleName());
        minecraft.gui.setScreen(new WaitingRoomScreen());
    }

    public static void onSnapshot(int serial, int entry, boolean paused, long time, long duration, boolean live) {
        if (!active || detached) {
            return;
        }
        lastSnapshot = new Snapshot(serial, entry, paused, time, duration, live);
        if (videoScreen != null) {
            videoScreen.applyRoomSnapshot(serial, entry, toSync(lastSnapshot));
        }
    }

    private static Sync toSync(Snapshot snapshot) {
        return new Sync(++revision, snapshot.paused() ? MediaPlayer.Status.PAUSED : MediaPlayer.Status.PLAYING,
                Math.max(0L, snapshot.time()), Math.max(0L, snapshot.duration()), 1f, false, snapshot.live());
    }

    // Uses what was prepared when it is the entry in play; a latecomer opens the entry the session is in.
    private static void openVideo(Minecraft minecraft) {
        ConditionalVideosConfig config = ActiveConfigResolver.resolve(minecraft);
        ConditionConfig condition = config.firstJoin();
        List<VideoEntry> playlist = condition == null ? List.of() : condition.resolvedPlaylist();
        int entry = lastSnapshot.entry();
        if (entry < 0 || entry >= playlist.size()) {
            return;
        }
        Function<VideoEntry, URI> sources = ConditionVideoPlayer.sourceResolver(minecraft, "waiting room");
        WaterMediaVideoBackend prepared = preparation != null && preparation.index() == entry ? preparation.backend() : null;
        URI source = prepared != null ? prepared.source() : sources.apply(playlist.get(entry));
        if (source == null) {
            return;
        }
        if (preparation != null && prepared == null) {
            preparation.cancel();
        }
        preparation = null;
        VideoAudioState.setLoadingActive(false);
        videoScreen = new VideoPlaybackScreen(config, condition, playlist, sources, source, CONDITION, WaitingRoomClient::onVideoClosed, prepared, entry, null);
        videoScreen.followRoom(entry);
        minecraft.gui.setScreen(videoScreen);
        videoScreen.applyRoomSnapshot(lastSnapshot.serial(), entry, toSync(lastSnapshot));
        DebugLog.client(DebugLog.Area.ROOM, "The video opened at entry {}, {} ms.", entry, lastSnapshot.time());
    }

    // Closed by this player's skip: they stay out of the session and go back to playing.
    private static void onVideoClosed() {
        if (closingForRoom || !active) {
            return;
        }
        videoScreen = null;
        lastSnapshot = null;
        detached = true;
        DebugLog.client(DebugLog.Area.ROOM, "Skipped out of the synchronized video.");
        PlaybackControlNetworking.sendRoomLeave();
    }

    private static void reportDuration() {
        int entry = videoScreen.currentEntryIndex();
        if (reportedEntry == entry) {
            return;
        }
        boolean live = videoScreen.currentIsLive();
        long duration = videoScreen.currentDurationMillis();
        if (live || duration > 0L) {
            reportedEntry = entry;
            PlaybackControlNetworking.sendRoomDuration(entry, duration, live);
        }
    }
}
