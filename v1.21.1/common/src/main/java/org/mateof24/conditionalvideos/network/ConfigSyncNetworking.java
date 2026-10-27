package org.mateof24.conditionalvideos.network;

import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.CommonConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.mateof24.conditionalvideos.debug.ServerDebug;
import org.mateof24.conditionalvideos.video.path.VideoSourceResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ConfigSyncNetworking {
    private static final ResourceLocation SERVER_CONFIG_PACKET =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "server_config_sync");
    private static final ResourceLocation SERVER_COMMON_CONFIG_PACKET =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "server_common_config_sync");
    private static final ResourceLocation SERVER_VIDEO_MANIFEST_PACKET =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "server_video_manifest");
    private static final ResourceLocation CLIENT_VIDEO_REQUEST_PACKET =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "client_video_request");
    private static final ResourceLocation CLIENT_SYNC_REQUEST_PACKET =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "client_sync_request");
    private static final ResourceLocation CLIENT_JOIN_VIDEO_STATE_PACKET =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "client_join_video_state");
    private static final ResourceLocation SERVER_VIDEO_START_PACKET =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "server_video_start");
    private static final ResourceLocation SERVER_VIDEO_CHUNK_PACKET =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "server_video_chunk");

    private static final int FILE_CHUNK_SIZE = 32 * 1024;
    private static final Map<String, DownloadState> CLIENT_DOWNLOADS = new HashMap<>();
    private static boolean clientSyncRequested;
    private static final Map<UUID, GameType> PLAYER_PRE_VIDEO_GAME_MODES = new HashMap<>();
    private static Map<ResourceLocation, NetworkHelper.S2CPacketHandler> s2cHandlers;

    private static final long SERVER_DATA_TTL_MILLIS = 5000L;
    private static ServerData cachedServerData;
    private static long serverDataExpiryMillis;

    private static final ExecutorService ASYNC_IO =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ConditionalVideos-IO");
                t.setDaemon(true);
                return t;
            });

    private ConfigSyncNetworking() {
    }

    public static void init() {
        s2cHandlers = new LinkedHashMap<>();

        s2cHandlers.put(SERVER_CONFIG_PACKET, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String json = buf.readUtf(262144);
            DebugLog.client(DebugLog.Area.NETWORK, "Received the server's conditions ({} bytes).", data.length);
            ActiveConfigResolver.setRemoteConfig(ConditionalVideosConfig.fromJson(json));
        });

        s2cHandlers.put(SERVER_COMMON_CONFIG_PACKET, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String json = buf.readUtf(262144);
            DebugLog.client(DebugLog.Area.NETWORK, "Received the server's common config.");
            ActiveConfigResolver.setRemoteCommonConfig(CommonConfig.fromAuthoritativeJson(json));
        });

        s2cHandlers.put(SERVER_VIDEO_MANIFEST_PACKET, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            int count = buf.readVarInt();
            List<VideoManifestEntry> manifest = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                manifest.add(new VideoManifestEntry(
                        buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readVarLong(), buf.readUtf()));
            }
            DebugLog.client(DebugLog.Area.NETWORK, "Received the server's video manifest: {} file(s).", count);
            ActiveConfigResolver.onManifestReceived();
            ASYNC_IO.execute(() -> {
                onClientManifest(manifest);
                ActiveConfigResolver.onManifestProcessed();
            });
        });

        s2cHandlers.put(SERVER_VIDEO_START_PACKET, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String configuredPath = buf.readUtf();
            String conditionType = buf.readUtf();
            String hash = buf.readUtf();
            String extension = buf.readUtf();
            int expectedChunks = buf.readVarInt();
            onClientVideoStart(configuredPath, conditionType, hash, extension, expectedChunks);
        });

        s2cHandlers.put(SERVER_VIDEO_CHUNK_PACKET, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String configuredPath = buf.readUtf();
            int chunkIndex = buf.readVarInt();
            int chunkLen = buf.readVarInt();
            byte[] chunkData = new byte[chunkLen];
            buf.readBytes(chunkData);
            onClientVideoChunk(configuredPath, chunkIndex, chunkData);
        });

        Map<ResourceLocation, NetworkHelper.C2SPacketHandler> c2sHandlers = new LinkedHashMap<>();

        c2sHandlers.put(CLIENT_VIDEO_REQUEST_PACKET, (data, player) -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String configuredPath = buf.readUtf();
            DebugLog.server(DebugLog.Area.TRANSFER, "{} requested '{}'.", player.getScoreboardName(), configuredPath);
            ASYNC_IO.execute(() -> sendVideoFile(player, configuredPath));
        });

        c2sHandlers.put(CLIENT_SYNC_REQUEST_PACKET, (data, player) -> {
            DebugLog.server(DebugLog.Area.NETWORK, "{} asked for the ConditionalVideos data.", player.getScoreboardName());
            sendServerData(player);
        });

        c2sHandlers.put(CLIENT_JOIN_VIDEO_STATE_PACKET, (data, player) -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            boolean playing = buf.readBoolean();
            onServerJoinVideoState(player, playing);
        });

        PlaybackControlNetworking.registerS2CHandlers(s2cHandlers);

        NetworkHelper.registerPackets(s2cHandlers, c2sHandlers);

        PlayerEvent.PLAYER_JOIN.register(ConfigSyncNetworking::sendServerData);
    }

    public static void initClient() {
        if (s2cHandlers != null) {
            NetworkHelper.registerS2CClientHandlers(s2cHandlers);
        }
    }

    private static void sendServerData(ServerPlayer player) {
        if (player.server.isDedicatedServer()) {
            sendServerConfig(player);
            sendServerCommonConfig(player);
            sendVideoManifest(player);
        } else {
            DebugLog.server(DebugLog.Area.NETWORK, "Not sending data to {}: an integrated server (singleplayer/LAN) does not sync its configuration.",
                    player.getScoreboardName());
        }
    }

    // Names of the players currently held in spectator for their firstJoin video.
    public static List<String> protectedPlayerNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        for (UUID uuid : PLAYER_PRE_VIDEO_GAME_MODES.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            names.add(player == null ? uuid.toString() : player.getScoreboardName());
        }
        return names;
    }

    // Loads the server config, common config and hashed video manifest once and caches them for a short
    // TTL. Without this every join reloaded the config from disk three times and every client file
    // request re-hashed every configured video; now a join and a burst of file requests share one load.
    // Synchronized because file requests run on the ASYNC_IO thread while joins run on the server thread.
    private static synchronized ServerData serverData(MinecraftServer server) {
        long now = System.currentTimeMillis();
        ServerData data = cachedServerData;
        if (data == null || now >= serverDataExpiryMillis) {
            Path root = server.getServerDirectory();
            ConditionalVideosConfig config = ConditionalVideosConfig.loadServer(root);
            CommonConfig common = CommonConfig.loadServer(root);
            ServerDebug.applyFlag(common.debugLogging());
            List<VideoManifestEntry> manifest = collectVideoEntries(config, root);
            data = new ServerData(config, common, manifest);
            cachedServerData = data;
            serverDataExpiryMillis = now + SERVER_DATA_TTL_MILLIS;
            ServerDebug.countConfigReload();
            if (DebugLog.serverEnabled()) {
                DebugLog.server(DebugLog.Area.CONFIG, "Loaded the server data: {} playable condition(s), {} file(s) in the video manifest ({} ms).",
                        ConditionRegistry.listPlayableKeys(config).size(), manifest.size(), System.currentTimeMillis() - now);
            }
        }
        return data;
    }

    public static void sendServerConfig(ServerPlayer player) {
        String json = serverData(player.server).config().toJson();
        byte[] data = NetworkHelper.toBytes(buf -> buf.writeUtf(json));
        NetworkHelper.sendToPlayer(player, SERVER_CONFIG_PACKET, data);
        DebugLog.server(DebugLog.Area.NETWORK, "Sent the conditions to {} ({} bytes).", player.getScoreboardName(), data.length);
    }

    public static void sendServerCommonConfig(ServerPlayer player) {
        String json = serverData(player.server).common().toAuthoritativeJson();
        byte[] data = NetworkHelper.toBytes(buf -> buf.writeUtf(json));
        NetworkHelper.sendToPlayer(player, SERVER_COMMON_CONFIG_PACKET, data);
        if (DebugLog.serverEnabled()) {
            DebugLog.server(DebugLog.Area.NETWORK, "Sent the common config to {}: {}", player.getScoreboardName(), json.replaceAll("\\s+", ""));
        }
    }

    private static void sendVideoManifest(ServerPlayer player) {
        List<VideoManifestEntry> entries = serverData(player.server).manifest();
        DebugLog.server(DebugLog.Area.NETWORK, "Sending the video manifest to {}: {} file(s).", player.getScoreboardName(), entries.size());
        byte[] data = NetworkHelper.toBytes(buf -> {
            buf.writeVarInt(entries.size());
            for (VideoManifestEntry entry : entries) {
                buf.writeUtf(entry.configuredPath());
                buf.writeUtf(entry.conditionType());
                buf.writeUtf(entry.hash());
                buf.writeVarLong(entry.size());
                buf.writeUtf(entry.extension());
            }
        });
        NetworkHelper.sendToPlayer(player, SERVER_VIDEO_MANIFEST_PACKET, data);
    }

    public static void notifyFirstJoinVideoState(boolean playing) {
        NetworkHelper.sendToServer(CLIENT_JOIN_VIDEO_STATE_PACKET,
                NetworkHelper.toBytes(buf -> buf.writeBoolean(playing)));
        DebugLog.client(DebugLog.Area.NETWORK, "Told the server the firstJoin video is {}.", playing ? "playing" : "over");
    }

    private static void onServerJoinVideoState(ServerPlayer player, boolean playing) {
        UUID uuid = player.getUUID();
        if (playing) {
            GameType current = player.gameMode.getGameModeForPlayer();
            GameType saved = PLAYER_PRE_VIDEO_GAME_MODES.putIfAbsent(uuid, current);
            if (current != GameType.SPECTATOR) {
                setServerPlayerGameMode(player, GameType.SPECTATOR);
            }
            DebugLog.server(DebugLog.Area.PROTECT, "{} started the firstJoin video: game mode {} -> spectator (saved mode {}).",
                    player.getScoreboardName(), current, saved == null ? current : saved);
            return;
        }
        GameType previous = PLAYER_PRE_VIDEO_GAME_MODES.remove(uuid);
        if (previous != null && player.gameMode.getGameModeForPlayer() != previous) {
            setServerPlayerGameMode(player, previous);
        }
        DebugLog.server(DebugLog.Area.PROTECT, "{} finished the firstJoin video: {}.", player.getScoreboardName(),
                previous == null ? "no saved game mode to restore" : "game mode restored to " + previous);
    }

    private static void setServerPlayerGameMode(ServerPlayer player, GameType gameType) {
        try {
            player.setGameMode(gameType);
        } catch (Throwable throwable) {
            DebugLog.server(DebugLog.Area.PROTECT, "Failed to set game mode {} for {}: {}", gameType, player.getScoreboardName(), throwable.toString());
        }
    }

    private static List<VideoManifestEntry> collectVideoEntries(ConditionalVideosConfig config, Path serverRoot) {
        List<VideoManifestEntry> entries = new ArrayList<>();
        addPlaylistEntries(entries, "join", config.firstJoin(), serverRoot);
        addPlaylistEntries(entries, "death", config.playerDeath(), serverRoot);
        config.entityKilled().forEach((key, value) -> addPlaylistEntries(entries, "kill_entity", value, serverRoot));
        config.deathByEntity().forEach((key, value) -> addPlaylistEntries(entries, "death_by_entity", value, serverRoot));
        config.advancementCompleted().forEach((key, value) -> addPlaylistEntries(entries, "advancement", value, serverRoot));
        config.dimensionChanged().forEach((key, value) -> addPlaylistEntries(entries, "dimension", value, serverRoot));
        addPlaylistEntries(entries, "totem_used", config.totemUsed(), serverRoot);
        addPlaylistEntries(entries, "bed_sleep", config.bedSleep(), serverRoot);
        config.itemObtained().forEach((key, value) -> addPlaylistEntries(entries, "item_obtained", value, serverRoot));
        config.itemCrafted().forEach((key, value) -> addPlaylistEntries(entries, "item_crafted", value, serverRoot));
        config.recipeUnlocked().forEach((key, value) -> addPlaylistEntries(entries, "recipe_unlocked", value, serverRoot));
        config.custom().forEach((key, value) -> addPlaylistEntries(entries, "custom", value, serverRoot));
        config.scoreboard().forEach((key, value) -> addPlaylistEntries(entries, "scoreboard", value.toConditionConfig(), serverRoot));
        return entries;
    }

    private static void addPlaylistEntries(List<VideoManifestEntry> entries, String conditionType,
                                           ConditionalVideosConfig.ConditionConfig conditionConfig, Path root) {
        if (conditionConfig == null) {
            return;
        }
        for (ConditionalVideosConfig.VideoEntry entry : conditionConfig.resolvedPlaylist()) {
            addVideoEntry(entries, conditionType, entry.source(), root);
        }
    }

    private static void addVideoEntry(List<VideoManifestEntry> entries, String conditionType, String configuredPath, Path root) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return;
        }
        if (VideoSourceResolver.looksLikeUrl(configuredPath)) {
            DebugLog.server(DebugLog.Area.TRANSFER, "Not adding {} URL '{}' to the manifest; clients open it directly.", conditionType, configuredPath);
            return;
        }
        Path resolved = resolveConfigPath(root, configuredPath);
        if (resolved == null || !Files.isRegularFile(resolved)) {
            ConditionalVideos.LOGGER.warn("Server config references {} video '{}' but the file is missing or invalid on the server filesystem. Clients will not receive it.", conditionType, configuredPath);
            return;
        }
        try {
            long size = Files.size(resolved);
            String hash = sha256(resolved);
            String extension = fileExtension(resolved.getFileName().toString());
            entries.add(new VideoManifestEntry(configuredPath, conditionType, hash, size, extension));
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.warn("Unable to hash configured video '{}'", configuredPath, exception);
        }
    }

    private static Path resolveConfigPath(Path root, String configuredPath) {
        Path raw = Path.of(configuredPath);
        return raw.isAbsolute() ? raw.normalize() : root.resolve(raw).normalize();
    }

    private static String fileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return ".mp4";
        }
        return fileName.substring(dot);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] content = Files.readAllBytes(file);
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception exception) {
            throw new IOException("Failed computing SHA-256 for " + file, exception);
        }
    }

    private static void onClientManifest(List<VideoManifestEntry> manifest) {
        Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
        String serverId = ActiveConfigResolver.resolveCurrentServerId(net.minecraft.client.Minecraft.getInstance());
        Path baseDir = gameDir.resolve("config").resolve("conditionalvideos").resolve(serverId);

        Set<String> requestedPaths = new HashSet<>();
        for (VideoManifestEntry entry : manifest) {
            ActiveConfigResolver.addManifestedVideoPath(entry.configuredPath());

            if (ActiveConfigResolver.resolveRemoteVideoPath(entry.configuredPath()) != null) {
                continue;
            }

            Path destination = buildClientVideoPath(baseDir, entry.conditionType(), entry.configuredPath(), entry.extension());
            boolean needsDownload = true;

            try {
                Files.createDirectories(destination.getParent());
                if (Files.isRegularFile(destination)) {
                    try {
                        if (entry.hash().equals(sha256(destination))) {
                            ActiveConfigResolver.setRemoteVideoPath(entry.configuredPath(), destination);
                            needsDownload = false;
                            DebugLog.client(DebugLog.Area.TRANSFER, "'{}': the cached copy '{}' matches the server's hash; no download needed.",
                                    entry.configuredPath(), destination);
                        }
                    } catch (IOException lockedEx) {
                        ActiveConfigResolver.setRemoteVideoPath(entry.configuredPath(), destination);
                        needsDownload = false;
                        DebugLog.client(DebugLog.Area.TRANSFER, "'{}': the cached copy '{}' is in use and cannot be hashed; using it as is.",
                                entry.configuredPath(), destination);
                    }
                }
            } catch (IOException exception) {
                ConditionalVideos.LOGGER.warn("Failed validating cached video '{}'", destination, exception);
                needsDownload = false;
            }

            if (needsDownload && !requestedPaths.contains(entry.configuredPath())) {
                requestedPaths.add(entry.configuredPath());
                DebugLog.client(DebugLog.Area.TRANSFER, "'{}' needs a download ({} KB, {}).", entry.configuredPath(), entry.size() / 1024,
                        Files.isRegularFile(destination) ? "the cached copy is outdated" : "no cached copy");
                net.minecraft.client.Minecraft.getInstance().execute(
                        () -> requestVideoFromServer(entry.configuredPath()));
            }
        }
    }

    private static Path buildClientVideoPath(Path baseDir, String conditionType, String configuredPath, String extension) {
        String key = Integer.toHexString(configuredPath.hashCode());
        String safeCondition = conditionType.replaceAll("[^a-zA-Z0-9._-]", "_");
        return baseDir.resolve(safeCondition).resolve(key + extension).normalize();
    }

    private static void requestVideoFromServer(String configuredPath) {
        NetworkHelper.sendToServer(CLIENT_VIDEO_REQUEST_PACKET,
                NetworkHelper.toBytes(buf -> buf.writeUtf(configuredPath)));
        DebugLog.client(DebugLog.Area.TRANSFER, "Requested '{}' from the server.", configuredPath);
    }

    private static void onClientVideoStart(String configuredPath, String conditionType, String hash, String extension, int expectedChunks) {
        if (CLIENT_DOWNLOADS.containsKey(configuredPath)) {
            DebugLog.client(DebugLog.Area.TRANSFER, "'{}' is already downloading; ignoring a second start.", configuredPath);
            return;
        }
        Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
        String serverId = ActiveConfigResolver.resolveCurrentServerId(net.minecraft.client.Minecraft.getInstance());
        Path baseDir = gameDir.resolve("config").resolve("conditionalvideos").resolve(serverId);
        Path target = buildClientVideoPath(baseDir, conditionType, configuredPath, extension);
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(temp);
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.warn("Failed preparing download target for '{}'", configuredPath, exception);
            return;
        }
        CLIENT_DOWNLOADS.put(configuredPath, new DownloadState(configuredPath, hash, target, temp, expectedChunks));
        DebugLog.client(DebugLog.Area.TRANSFER, "Download of '{}' started: {} chunk(s) into '{}'.", configuredPath, expectedChunks, target);
    }

    private static void onClientVideoChunk(String configuredPath, int chunkIndex, byte[] data) {
        DownloadState state = CLIENT_DOWNLOADS.get(configuredPath);
        if (state == null) return;

        if (chunkIndex != state.receivedChunks) {
            ConditionalVideos.LOGGER.warn("Unexpected chunk order for '{}': got {}, expected {}", configuredPath, chunkIndex, state.receivedChunks);
            CLIENT_DOWNLOADS.remove(configuredPath);
            return;
        }

        state.receivedChunks++;
        boolean complete = state.receivedChunks >= state.expectedChunks;
        if (complete) CLIENT_DOWNLOADS.remove(configuredPath);
        int quarter = state.expectedChunks <= 0 ? 4 : state.receivedChunks * 4 / state.expectedChunks;
        if (quarter > state.loggedQuarter && quarter < 4) {
            state.loggedQuarter = quarter;
            DebugLog.client(DebugLog.Area.TRANSFER, "Download of '{}': {}% ({}/{} chunks).", configuredPath, quarter * 25,
                    state.receivedChunks, state.expectedChunks);
        }

        ASYNC_IO.execute(() -> {
            try {
                Files.write(state.tempPath, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            } catch (IOException exception) {
                ConditionalVideos.LOGGER.warn("Failed writing chunk {} for '{}'", chunkIndex, configuredPath, exception);
                return;
            }
            if (complete) finalizeDownload(state);
        });
    }

    private static void finalizeDownload(DownloadState state) {
        try {
            String downloadedHash = sha256(state.tempPath);
            if (!state.hash.equals(downloadedHash)) {
                ConditionalVideos.LOGGER.warn("Video hash mismatch for '{}'. Expected {}, got {}", state.configuredPath, state.hash, downloadedHash);
                Files.deleteIfExists(state.tempPath);
                return;
            }

            try {
                Files.move(state.tempPath, state.targetPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveEx) {
                if (Files.isRegularFile(state.targetPath)) {
                    ConditionalVideos.LOGGER.warn("Could not replace video '{}' (file in use), using existing copy.", state.configuredPath);
                    Files.deleteIfExists(state.tempPath);
                } else {
                    throw moveEx;
                }
            }

            ActiveConfigResolver.setRemoteVideoPath(state.configuredPath, state.targetPath);
            if (DebugLog.clientEnabled()) {
                long millis = Math.max(1L, (System.nanoTime() - state.startNanos) / 1_000_000L);
                long kilobytes = Files.size(state.targetPath) / 1024;
                DebugLog.client(DebugLog.Area.TRANSFER, "Download of '{}' finished: {} KB in {} ms ({} KB/s), hash verified.",
                        state.configuredPath, kilobytes, millis, kilobytes * 1000 / millis);
            }
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.warn("Failed finalizing video '{}'", state.configuredPath, exception);
        }
    }

    private static void sendVideoFile(ServerPlayer player, String configuredPath) {
        List<VideoManifestEntry> entries = serverData(player.server).manifest();
        VideoManifestEntry targetEntry = entries.stream()
                .filter(entry -> entry.configuredPath().equals(configuredPath))
                .findFirst().orElse(null);
        if (targetEntry == null) {
            DebugLog.server(DebugLog.Area.TRANSFER, "'{}' requested by {} is not in the manifest; ignoring it.",
                    configuredPath, player.getScoreboardName());
            return;
        }

        Path source = resolveConfigPath(player.server.getServerDirectory(), configuredPath);
        if (source == null || !Files.isRegularFile(source)) {
            DebugLog.server(DebugLog.Area.TRANSFER, "'{}' requested by {} is missing on disk; ignoring it.",
                    configuredPath, player.getScoreboardName());
            return;
        }

        long size;
        try {
            size = Files.size(source);
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.warn("Failed reading configured video '{}'", configuredPath, exception);
            return;
        }

        int expectedChunks = (int) ((size + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE);
        final String conditionType = targetEntry.conditionType();
        final String hash = targetEntry.hash();
        final String extension = targetEntry.extension();

        NetworkHelper.sendToPlayer(player, SERVER_VIDEO_START_PACKET, NetworkHelper.toBytes(buf -> {
            buf.writeUtf(configuredPath);
            buf.writeUtf(conditionType);
            buf.writeUtf(hash);
            buf.writeUtf(extension);
            buf.writeVarInt(expectedChunks);
        }));
        long startNanos = System.nanoTime();
        ServerDebug.countTransferStarted();
        DebugLog.server(DebugLog.Area.TRANSFER, "Sending '{}' to {}: {} KB in {} chunk(s).", configuredPath,
                player.getScoreboardName(), size / 1024, expectedChunks);

        // Stream the file in chunks straight from disk instead of holding the whole video in memory.
        try (InputStream in = Files.newInputStream(source)) {
            byte[] buffer = new byte[FILE_CHUNK_SIZE];
            int index = 0;
            int read;
            while ((read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                final int chunkIdx = index;
                final int length = read;
                final byte[] payload = buffer;
                NetworkHelper.sendToPlayer(player, SERVER_VIDEO_CHUNK_PACKET, NetworkHelper.toBytes(buf -> {
                    buf.writeUtf(configuredPath);
                    buf.writeVarInt(chunkIdx);
                    buf.writeVarInt(length);
                    buf.writeBytes(payload, 0, length);
                }));
                index++;
            }
            ServerDebug.countTransferFinished(size);
            DebugLog.server(DebugLog.Area.TRANSFER, "Queued all {} chunk(s) of '{}' for {} in {} ms.", index, configuredPath,
                    player.getScoreboardName(), (System.nanoTime() - startNanos) / 1_000_000L);
        } catch (IOException exception) {
            ServerDebug.countTransferFailed();
            ConditionalVideos.LOGGER.warn("Failed reading configured video '{}'", configuredPath, exception);
        }
    }

    public static void onClientDisconnected() {
        ActiveConfigResolver.resetRemoteSessionState();
        if (!CLIENT_DOWNLOADS.isEmpty()) {
            DebugLog.client(DebugLog.Area.TRANSFER, "Dropped {} unfinished download(s) on disconnect.", CLIENT_DOWNLOADS.size());
        }
        CLIENT_DOWNLOADS.clear();
        clientSyncRequested = false;
    }

    public static void requestServerSync(boolean force) {
        if (clientSyncRequested && !force) return;
        NetworkHelper.sendToServer(CLIENT_SYNC_REQUEST_PACKET, new byte[0]);
        DebugLog.client(DebugLog.Area.NETWORK, "Asked the server for its ConditionalVideos data{}.", clientSyncRequested ? " (retry)" : "");
        clientSyncRequested = true;
    }

    private record VideoManifestEntry(String configuredPath, String conditionType, String hash, long size,
                                      String extension) {
    }

    private record ServerData(ConditionalVideosConfig config, CommonConfig common,
                              List<VideoManifestEntry> manifest) {
    }

    private static final class DownloadState {
        private final String configuredPath;
        private final String hash;
        private final Path targetPath;
        private final Path tempPath;
        private final int expectedChunks;
        private final long startNanos = System.nanoTime();
        private int receivedChunks;
        private int loggedQuarter;

        private DownloadState(String configuredPath, String hash, Path targetPath, Path tempPath, int expectedChunks) {
            this.configuredPath = configuredPath;
            this.hash = hash;
            this.targetPath = targetPath;
            this.tempPath = tempPath;
            this.expectedChunks = expectedChunks;
            this.receivedChunks = 0;
        }
    }
}
