package org.mateof24.conditionalvideos.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.runtime.PlaybackControlClient;
import org.mateof24.conditionalvideos.runtime.PlaybackEvents;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Public, stable entry point for other mods to interact with ConditionalVideos.
 *
 * <p><b>Where things run.</b> Video playback always happens on the <i>client</i>. The server can
 * only <i>order</i> a specific client to play/stop/pause through the mod's S2C control channel.
 * Therefore:</p>
 * <ul>
 *   <li><b>Server logic</b> (e.g. a custom condition evaluated on the server) should call the
 *       {@code *OnServer} methods with a {@link ServerPlayer}; the order is routed to that player's
 *       client.</li>
 *   <li><b>Client logic</b> (client-only mods, or singleplayer convenience) may call the
 *       {@code *OnClient} methods directly.</li>
 * </ul>
 *
 * <p>All methods are null-safe and side-effect-free when called in the wrong environment (e.g.
 * {@code playOnClient} on a dedicated server is a no-op), so callers do not need their own guards.</p>
 */
public final class ConditionalVideosAPI {
    private ConditionalVideosAPI() {
    }

    /**
     * Wires the API's own lifecycle hooks. Called once by the mod during init; not for external use.
     */
    public static void init() {
        LifecycleEvent.SERVER_STARTING.register(ConditionalVideosAPI::seedRegisteredCustomConditions);
    }

    // ---------------------------------------------------------------------
    // Custom condition registration
    // ---------------------------------------------------------------------

    /**
     * Registers a custom condition so it is dispatchable by id and discoverable in config.
     *
     * <p>Registered conditions are seeded into the active config file as empty entries when a world
     * starts (only when the file does not yet contain them; existing entries are never altered, and a
     * config that cannot be parsed is left untouched). Admins add videos to {@code custom/<id>} to make
     * it playable.</p>
     */
    public static void registerCustomCondition(CustomCondition condition) {
        if (condition == null) {
            return;
        }
        CustomConditionRegistry.register(condition);
    }

    public static void registerCustomCondition(String id) {
        registerCustomCondition(new CustomCondition(id));
    }

    public static void registerCustomCondition(String id, String displayName, String description) {
        registerCustomCondition(new CustomCondition(id, displayName, description));
    }

    public static boolean isCustomConditionRegistered(String id) {
        return CustomConditionRegistry.isRegistered(id);
    }

    public static Collection<CustomCondition> registeredCustomConditions() {
        return CustomConditionRegistry.all();
    }

    // ---------------------------------------------------------------------
    // Server-side triggering (routes to the target player's client via S2C)
    // ---------------------------------------------------------------------

    /**
     * Triggers a condition for a player, honoring its "first time / repeatable" gating.
     *
     * @param conditionKey a config key such as {@code firstJoin}, {@code itemObtained/minecraft:diamond}
     *                     or {@code custom/my_event}
     * @return {@code true} if an order was sent (condition resolved, has videos, and passed the session gate)
     */
    public static boolean triggerOnServer(ServerPlayer player, String conditionKey) {
        if (player == null || conditionKey == null || conditionKey.isBlank()) {
            return false;
        }
        return ServerConditionDispatcher.fire(player, conditionKey.trim());
    }

    /**
     * Convenience for {@link #triggerOnServer(ServerPlayer, String)} with a {@code custom/<id>} key.
     */
    public static boolean triggerCustomOnServer(ServerPlayer player, String customId) {
        if (customId == null || customId.isBlank()) {
            return false;
        }
        return triggerOnServer(player, ConditionRegistry.TYPE_CUSTOM + "/" + customId.trim());
    }

    /**
     * Plays a condition for a player regardless of its session gating (mirrors the {@code play} command).
     *
     * @return {@code true} if an order was sent (condition resolved and has at least one video)
     */
    public static boolean forcePlayOnServer(ServerPlayer player, String conditionKey) {
        if (player == null || conditionKey == null || conditionKey.isBlank()) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        String key = conditionKey.trim();
        ConditionalVideosConfig.ConditionConfig resolved =
                ConditionRegistry.resolve(ServerConditionDispatcher.activeConfig(server), key);
        if (resolved == null || resolved.resolvedPlaylist().isEmpty()) {
            return false;
        }
        PlaybackControlNetworking.sendPlay(player, key);
        return true;
    }

    public static void stopOnServer(ServerPlayer player) {
        if (player != null) {
            PlaybackControlNetworking.sendStop(player);
        }
    }

    public static void togglePauseOnServer(ServerPlayer player) {
        if (player != null) {
            PlaybackControlNetworking.sendPause(player);
        }
    }

    // ---------------------------------------------------------------------
    // Client-side triggering (client-only mods / singleplayer convenience)
    // ---------------------------------------------------------------------

    public static void playOnClient(String conditionKey) {
        if (isClient() && conditionKey != null && !conditionKey.isBlank()) {
            PlaybackControlClient.play(conditionKey.trim());
        }
    }

    public static void stopOnClient() {
        if (isClient()) {
            PlaybackControlClient.stop();
        }
    }

    public static void togglePauseOnClient() {
        if (isClient()) {
            PlaybackControlClient.togglePause();
        }
    }

    // ---------------------------------------------------------------------
    // Client-side state queries
    // ---------------------------------------------------------------------

    /**
     * @return {@code true} if a video is currently playing on this client.
     */
    public static boolean isPlaying() {
        return isClient() && Minecraft.getInstance().screen instanceof VideoPlaybackScreen;
    }

    /**
     * @return {@code true} if a video is playing and currently paused on this client.
     */
    public static boolean isPaused() {
        return isClient()
                && Minecraft.getInstance().screen instanceof VideoPlaybackScreen screen
                && screen.isPaused();
    }

    /**
     * @return the condition key currently playing on this client, if any.
     */
    public static Optional<String> currentConditionKey() {
        return Optional.ofNullable(PlaybackEvents.currentConditionKey());
    }

    // ---------------------------------------------------------------------
    // Client-side playback events
    // ---------------------------------------------------------------------

    public static void addPlaybackListener(PlaybackListener listener) {
        PlaybackEvents.addListener(listener);
    }

    public static void removePlaybackListener(PlaybackListener listener) {
        PlaybackEvents.removeListener(listener);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private static boolean isClient() {
        return Platform.getEnvironment() == Env.CLIENT && Minecraft.getInstance() != null;
    }

    private static void seedRegisteredCustomConditions(MinecraftServer server) {
        Collection<CustomCondition> customs = CustomConditionRegistry.all();
        if (customs.isEmpty()) {
            return;
        }
        try {
            Path directory = server.getServerDirectory();
            Path file = server.isDedicatedServer()
                    ? ConditionalVideosConfig.serverConfigPath(directory)
                    : ConditionalVideosConfig.clientConfigPath(directory);

            // Config-sanctity: never rewrite a file we cannot parse cleanly.
            if (Files.isRegularFile(file) && !isParseableJsonObject(file)) {
                ConditionalVideos.LOGGER.warn("Skipping custom-condition seeding: '{}' is not valid JSON; leaving it untouched.", file);
                return;
            }

            ConditionalVideosConfig config = ConditionalVideosConfig.load(file);
            boolean changed = false;
            for (CustomCondition custom : customs) {
                if (!config.custom().containsKey(custom.id())) {
                    config.custom().put(custom.id(), ConditionalVideosConfig.emptyConditionConfig());
                    changed = true;
                }
            }
            if (changed) {
                config.save(file);
                ConditionalVideos.LOGGER.info("Seeded {} registered custom condition(s) into '{}'.", customs.size(), file);
            }
        } catch (Throwable t) {
            ConditionalVideos.LOGGER.debug("Failed to seed registered custom conditions: {}", t.toString());
        }
    }

    private static boolean isParseableJsonObject(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed != null && parsed.isJsonObject();
        } catch (Throwable t) {
            return false;
        }
    }
}
