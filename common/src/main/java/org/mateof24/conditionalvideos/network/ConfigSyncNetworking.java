package org.mateof24.conditionalvideos.network;

import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;


public final class ConfigSyncNetworking {
    private static final ResourceLocation SERVER_CONFIG_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "server_config_sync");
    private static final ResourceLocation SERVER_COMMON_CONFIG_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "server_common_config_sync");
    private static final ResourceLocation SERVER_VIDEO_MANIFEST_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "server_video_manifest");
    private static final ResourceLocation CLIENT_VIDEO_REQUEST_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "client_video_request");
    private static final ResourceLocation CLIENT_SYNC_REQUEST_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "client_sync_request");
    private static final ResourceLocation CLIENT_JOIN_VIDEO_STATE_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "client_join_video_state");
    private static final ResourceLocation SERVER_VIDEO_START_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "server_video_start");
    private static final ResourceLocation SERVER_VIDEO_CHUNK_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "server_video_chunk");

    private static final int FILE_CHUNK_SIZE = 32 * 1024;

    private static final Map<String, DownloadState> CLIENT_DOWNLOADS = new HashMap<>();
    private static boolean clientSyncRequested;
    private static final Map<UUID, GameType> PLAYER_PRE_VIDEO_GAME_MODES = new HashMap<>();

    private static final long SERVER_DATA_TTL_MILLIS = 5000L;
    private static ServerData cachedServerData;
    private static long serverDataExpiryMillis;

    private ConfigSyncNetworking() {
    }

    public static void init() {
        if (Platform.getEnvironment() == Env.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.s2c(), SERVER_CONFIG_PACKET, (buffer, context) -> {
                String json = buffer.readUtf(262144);
                context.queue(() -> ActiveConfigResolver.setRemoteConfig(ConditionalVideosConfig.fromJson(json)));
            });

            NetworkManager.registerReceiver(NetworkManager.s2c(), SERVER_COMMON_CONFIG_PACKET, (buffer, context) -> {
                String json = buffer.readUtf(262144);
                context.queue(() -> ActiveConfigResolver.setRemoteCommonConfig(CommonConfig.fromAuthoritativeJson(json)));
            });

            NetworkManager.registerReceiver(NetworkManager.s2c(), SERVER_VIDEO_MANIFEST_PACKET, (buffer, context) -> {
                int entries = buffer.readVarInt();
                List<VideoManifestEntry> manifest = new ArrayList<>();
                for (int i = 0; i < entries; i++) {
                    manifest.add(new VideoManifestEntry(
                            buffer.readUtf(),
                            buffer.readUtf(),
                            buffer.readUtf(),
                            buffer.readVarLong(),
                            buffer.readUtf()
                    ));
                }
                context.queue(() -> onClientManifest(manifest));
            });

            NetworkManager.registerReceiver(NetworkManager.s2c(), SERVER_VIDEO_START_PACKET, (buffer, context) -> {
                String configuredPath = buffer.readUtf();
                String conditionType = buffer.readUtf();
                String hash = buffer.readUtf();
                String extension = buffer.readUtf();
                int expectedChunks = buffer.readVarInt();
                context.queue(() -> onClientVideoStart(configuredPath, conditionType, hash, extension, expectedChunks));
            });

            NetworkManager.registerReceiver(NetworkManager.s2c(), SERVER_VIDEO_CHUNK_PACKET, (buffer, context) -> {
                String configuredPath = buffer.readUtf();
                int chunkIndex = buffer.readVarInt();
                int chunkLen = buffer.readVarInt();
                byte[] data = new byte[chunkLen];
                buffer.readBytes(data);
                context.queue(() -> onClientVideoChunk(configuredPath, chunkIndex, data));
            });

        }

        NetworkManager.registerReceiver(NetworkManager.c2s(), CLIENT_VIDEO_REQUEST_PACKET, (buffer, context) -> {
            String configuredPath = buffer.readUtf();
            context.queue(() -> {
                if (!(context.getPlayer() instanceof ServerPlayer player)) {
                    return;
                }
                sendVideoFile(player, configuredPath);
            });
        });

        NetworkManager.registerReceiver(NetworkManager.c2s(), CLIENT_SYNC_REQUEST_PACKET, (buffer, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof ServerPlayer player)) {
                    return;
                }
                sendServerData(player);
            });
        });

        NetworkManager.registerReceiver(NetworkManager.c2s(), CLIENT_JOIN_VIDEO_STATE_PACKET, (buffer, context) -> {
            boolean playing = buffer.readBoolean();
            context.queue(() -> {
                if (!(context.getPlayer() instanceof ServerPlayer player)) {
                    return;
                }
                onServerJoinVideoState(player, playing);
            });
        });

        PlayerEvent.PLAYER_JOIN.register(ConfigSyncNetworking::sendServerData);

    }

    private static void sendServerData(ServerPlayer player) {
        if (player.server.isDedicatedServer()) {
            sendServerConfig(player);
            sendServerCommonConfig(player);
            sendVideoManifest(player);
        }
    }

    // Loads the server config, common config and hashed video manifest once and caches them for a short
    // TTL. Without this every join reloaded the config from disk three times and every client file
    // request re-hashed every configured video; now a join and a burst of file requests share one load.
    private static ServerData serverData(MinecraftServer server) {
        long now = System.currentTimeMillis();
        ServerData data = cachedServerData;
        if (data == null || now >= serverDataExpiryMillis) {
            Path root = server.getServerDirectory().toPath();
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
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(serverData(player.server).config().toJson());
        NetworkManager.sendToPlayer(player, SERVER_CONFIG_PACKET, buffer);
    }

    public static void sendServerCommonConfig(ServerPlayer player) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(serverData(player.server).common().toAuthoritativeJson());
        NetworkManager.sendToPlayer(player, SERVER_COMMON_CONFIG_PACKET, buffer);
    }

    private static void sendVideoManifest(ServerPlayer player) {
        List<VideoManifestEntry> entries = serverData(player.server).manifest();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(entries.size());
        for (VideoManifestEntry entry : entries) {
            buffer.writeUtf(entry.configuredPath());
            buffer.writeUtf(entry.conditionType());
            buffer.writeUtf(entry.hash());
            buffer.writeVarLong(entry.size());
            buffer.writeUtf(entry.extension());
        }
        NetworkManager.sendToPlayer(player, SERVER_VIDEO_MANIFEST_PACKET, buffer);
    }

    public static void notifyFirstJoinVideoState(boolean playing) {
        FriendlyByteBuf request = new FriendlyByteBuf(Unpooled.buffer());
        request.writeBoolean(playing);
        NetworkManager.sendToServer(CLIENT_JOIN_VIDEO_STATE_PACKET, request);
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

        for (VideoManifestEntry entry : manifest) {
            ActiveConfigResolver.addManifestedVideoPath(entry.configuredPath());

            Path destination = buildClientVideoPath(baseDir, entry.conditionType(), entry.configuredPath(), entry.extension());
            try {
                Files.createDirectories(destination.getParent());
                if (Files.isRegularFile(destination) && entry.hash().equals(sha256(destination))) {
                    ActiveConfigResolver.setRemoteVideoPath(entry.configuredPath(), destination);
                    continue;
                }
            } catch (IOException exception) {
                ConditionalVideos.LOGGER.warn("Failed validating cached video '{}'", destination, exception);
            }

            requestVideoFromServer(entry.configuredPath());
        }
    }

    private static Path buildClientVideoPath(Path baseDir, String conditionType, String configuredPath, String extension) {
        String key = Integer.toHexString(configuredPath.hashCode());
        String safeCondition = conditionType.replaceAll("[^a-zA-Z0-9._-]", "_");
        return baseDir.resolve(safeCondition).resolve(key + extension).normalize();
    }

    private static void requestVideoFromServer(String configuredPath) {
        FriendlyByteBuf request = new FriendlyByteBuf(Unpooled.buffer());
        request.writeUtf(configuredPath);
        NetworkManager.sendToServer(CLIENT_VIDEO_REQUEST_PACKET, request);
    }

    private static void onClientVideoStart(String configuredPath, String conditionType, String hash, String extension, int expectedChunks) {
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
        if (state == null) {
            return;
        }

        if (chunkIndex != state.receivedChunks) {
            ConditionalVideos.LOGGER.warn("Unexpected chunk order for '{}': got {}, expected {}", configuredPath, chunkIndex, state.receivedChunks);
            CLIENT_DOWNLOADS.remove(configuredPath);
            return;
        }

        try {
            Files.write(state.tempPath, data,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.warn("Failed writing chunk {} for '{}'", chunkIndex, configuredPath, exception);
            CLIENT_DOWNLOADS.remove(configuredPath);
            return;
        }

        state.receivedChunks++;
        if (state.receivedChunks >= state.expectedChunks) {
            finalizeDownload(state);
            CLIENT_DOWNLOADS.remove(configuredPath);
        }
    }

    private static void finalizeDownload(DownloadState state) {
        try {
            String downloadedHash = sha256(state.tempPath);
            if (!state.hash.equals(downloadedHash)) {
                ConditionalVideos.LOGGER.warn("Video hash mismatch for '{}'. Expected {}, got {}", state.configuredPath, state.hash, downloadedHash);
                Files.deleteIfExists(state.tempPath);
                return;
            }
            Files.move(state.tempPath, state.targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ActiveConfigResolver.setRemoteVideoPath(state.configuredPath, state.targetPath);
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.warn("Failed finalizing video '{}'", state.configuredPath, exception);
        }
    }

    private static void sendVideoFile(ServerPlayer player, String configuredPath) {
        List<VideoManifestEntry> entries = serverData(player.server).manifest();
        VideoManifestEntry targetEntry = entries.stream()
                .filter(entry -> entry.configuredPath().equals(configuredPath))
                .findFirst()
                .orElse(null);
        if (targetEntry == null) {
            return;
        }

        Path source = resolveConfigPath(player.server.getServerDirectory().toPath(), configuredPath);
        if (source == null || !Files.isRegularFile(source)) {
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
        FriendlyByteBuf start = new FriendlyByteBuf(Unpooled.buffer());
        start.writeUtf(configuredPath);
        start.writeUtf(targetEntry.conditionType());
        start.writeUtf(targetEntry.hash());
        start.writeUtf(targetEntry.extension());
        start.writeVarInt(expectedChunks);
        NetworkManager.sendToPlayer(player, SERVER_VIDEO_START_PACKET, start);

        // Stream the file in chunks straight from disk instead of holding the whole video in memory.
        try (InputStream in = Files.newInputStream(source)) {
            byte[] buffer = new byte[FILE_CHUNK_SIZE];
            int index = 0;
            int read;
            while ((read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                FriendlyByteBuf chunk = new FriendlyByteBuf(Unpooled.buffer());
                chunk.writeUtf(configuredPath);
                chunk.writeVarInt(index);
                chunk.writeVarInt(read);
                chunk.writeBytes(buffer, 0, read);
                NetworkManager.sendToPlayer(player, SERVER_VIDEO_CHUNK_PACKET, chunk);
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
        if (clientSyncRequested && !force) {
            return;
        }
        FriendlyByteBuf request = new FriendlyByteBuf(Unpooled.buffer());
        NetworkManager.sendToServer(CLIENT_SYNC_REQUEST_PACKET, request);
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