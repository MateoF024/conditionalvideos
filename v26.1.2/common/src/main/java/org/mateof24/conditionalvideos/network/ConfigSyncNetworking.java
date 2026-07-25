package org.mateof24.conditionalvideos.network;

import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.CommonConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
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
    private static final Identifier SERVER_CONFIG_PACKET =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "server_config_sync");
    private static final Identifier SERVER_COMMON_CONFIG_PACKET =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "server_common_config_sync");
    private static final Identifier SERVER_VIDEO_MANIFEST_PACKET =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "server_video_manifest");
    private static final Identifier CLIENT_VIDEO_REQUEST_PACKET =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "client_video_request");
    private static final Identifier CLIENT_SYNC_REQUEST_PACKET =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "client_sync_request");
    private static final Identifier CLIENT_JOIN_VIDEO_STATE_PACKET =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "client_join_video_state");
    private static final Identifier SERVER_VIDEO_START_PACKET =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "server_video_start");
    private static final Identifier SERVER_VIDEO_CHUNK_PACKET =
            Identifier.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "server_video_chunk");

    private static final int FILE_CHUNK_SIZE = 32 * 1024;
    private static final Map<String, DownloadState> CLIENT_DOWNLOADS = new HashMap<>();
    private static boolean clientSyncRequested;
    private static final Map<UUID, GameType> PLAYER_PRE_VIDEO_GAME_MODES = new HashMap<>();
    private static Map<Identifier, NetworkHelper.S2CPacketHandler> s2cHandlers;

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
            ActiveConfigResolver.setRemoteConfig(ConditionalVideosConfig.fromJson(json));
        });

        s2cHandlers.put(SERVER_COMMON_CONFIG_PACKET, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String json = buf.readUtf(262144);
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

        Map<Identifier, NetworkHelper.C2SPacketHandler> c2sHandlers = new LinkedHashMap<>();

        c2sHandlers.put(CLIENT_VIDEO_REQUEST_PACKET, (data, player) -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String configuredPath = buf.readUtf();
            ASYNC_IO.execute(() -> sendVideoFile(player, configuredPath));
        });

        c2sHandlers.put(CLIENT_SYNC_REQUEST_PACKET, (data, player) -> sendServerData(player));

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
        if (player.level().getServer().isDedicatedServer()) {
            sendServerConfig(player);
            sendServerCommonConfig(player);
            sendVideoManifest(player);
        }
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
            List<VideoManifestEntry> manifest = collectVideoEntries(config, root);
            data = new ServerData(config, common, manifest);
            cachedServerData = data;
            serverDataExpiryMillis = now + SERVER_DATA_TTL_MILLIS;
        }
        return data;
    }

    public static void sendServerConfig(ServerPlayer player) {
        String json = serverData(player.level().getServer()).config().toJson();
        byte[] data = NetworkHelper.toBytes(buf -> buf.writeUtf(json));
        NetworkHelper.sendToPlayer(player, SERVER_CONFIG_PACKET, data);
    }

    public static void sendServerCommonConfig(ServerPlayer player) {
        String json = serverData(player.level().getServer()).common().toAuthoritativeJson();
        byte[] data = NetworkHelper.toBytes(buf -> buf.writeUtf(json));
        NetworkHelper.sendToPlayer(player, SERVER_COMMON_CONFIG_PACKET, data);
    }

    private static void sendVideoManifest(ServerPlayer player) {
        List<VideoManifestEntry> entries = serverData(player.level().getServer()).manifest();
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
    }

    private static void onServerJoinVideoState(ServerPlayer player, boolean playing) {
        UUID uuid = player.getUUID();
        if (playing) {
            GameType current = player.gameMode.getGameModeForPlayer();
            PLAYER_PRE_VIDEO_GAME_MODES.putIfAbsent(uuid, current);
            if (current != GameType.SPECTATOR) {
                setServerPlayerGameMode(player, GameType.SPECTATOR);
            }
            return;
        }
        GameType previous = PLAYER_PRE_VIDEO_GAME_MODES.remove(uuid);
        if (previous != null && player.gameMode.getGameModeForPlayer() != previous) {
            setServerPlayerGameMode(player, previous);
        }
    }

    private static void setServerPlayerGameMode(ServerPlayer player, GameType gameType) {
        try {
            player.setGameMode(gameType);
        } catch (Throwable throwable) {
            ConditionalVideos.LOGGER.debug("Failed to set game mode for '{}': {}", player.getScoreboardName(), throwable.toString());
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
            ConditionalVideos.LOGGER.debug("Skipping {} URL entry '{}' from manifest; clients will resolve it directly.", conditionType, configuredPath);
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
                        }
                    } catch (IOException lockedEx) {
                        ActiveConfigResolver.setRemoteVideoPath(entry.configuredPath(), destination);
                        needsDownload = false;
                    }
                }
            } catch (IOException exception) {
                ConditionalVideos.LOGGER.warn("Failed validating cached video '{}'", destination, exception);
                needsDownload = false;
            }

            if (needsDownload && !requestedPaths.contains(entry.configuredPath())) {
                requestedPaths.add(entry.configuredPath());
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
    }

    private static void onClientVideoStart(String configuredPath, String conditionType, String hash, String extension, int expectedChunks) {
        if (CLIENT_DOWNLOADS.containsKey(configuredPath)) {
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
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.warn("Failed finalizing video '{}'", state.configuredPath, exception);
        }
    }

    private static void sendVideoFile(ServerPlayer player, String configuredPath) {
        List<VideoManifestEntry> entries = serverData(player.level().getServer()).manifest();
        VideoManifestEntry targetEntry = entries.stream()
                .filter(entry -> entry.configuredPath().equals(configuredPath))
                .findFirst().orElse(null);
        if (targetEntry == null) return;

        Path source = resolveConfigPath(player.level().getServer().getServerDirectory(), configuredPath);
        if (source == null || !Files.isRegularFile(source)) return;

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
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.warn("Failed reading configured video '{}'", configuredPath, exception);
        }
    }

    public static void onClientDisconnected() {
        ActiveConfigResolver.resetRemoteSessionState();
        CLIENT_DOWNLOADS.clear();
        clientSyncRequested = false;
    }

    public static void requestServerSync(boolean force) {
        if (clientSyncRequested && !force) return;
        NetworkHelper.sendToServer(CLIENT_SYNC_REQUEST_PACKET, new byte[0]);
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
        private int receivedChunks;

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
