package org.mateof24.conditionalvideos.network;

import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;


public final class ConfigSyncNetworking {
    private static final ResourceLocation SERVER_CONFIG_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "server_config_sync");
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

    private ConfigSyncNetworking() {
    }

    public static void init() {
        if (Platform.getEnvironment() == Env.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.s2c(), SERVER_CONFIG_PACKET, (buffer, context) -> {
                String json = buffer.readUtf(262144);
                context.queue(() -> ActiveConfigResolver.setRemoteConfig(ConditionalVideosConfig.fromJson(json)));
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
            sendVideoManifest(player);
        }
    }


    public static void sendServerConfig(ServerPlayer player) {
        ConditionalVideosConfig serverConfig = ConditionalVideosConfig.loadServer(player.server.getServerDirectory().toPath());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(serverConfig.toJson());
        NetworkManager.sendToPlayer(player, SERVER_CONFIG_PACKET, buffer);
    }

    private static void sendVideoManifest(ServerPlayer player) {
        ConditionalVideosConfig serverConfig = ConditionalVideosConfig.loadServer(player.server.getServerDirectory().toPath());
        List<VideoManifestEntry> entries = collectVideoEntries(serverConfig, player.server.getServerDirectory().toPath());
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
            return;
        } catch (Throwable ignored) {
            // Try reflective fallbacks below.
        }
        try {
            for (java.lang.reflect.Method method : player.getClass().getMethods()) {
                if (!"setGameMode".equals(method.getName()) || method.getParameterCount() != 1) {
                    continue;
                }
                if (method.getParameterTypes()[0] == GameType.class) {
                    method.invoke(player, gameType);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static List<VideoManifestEntry> collectVideoEntries(ConditionalVideosConfig config, Path serverRoot) {
        List<VideoManifestEntry> entries = new ArrayList<>();
        addVideoEntry(entries, "join", config.firstJoin().video(), serverRoot);
        addVideoEntry(entries, "death", config.playerDeath().video(), serverRoot);
        config.entityKilled().forEach((key, value) -> addVideoEntry(entries, "kill_entity", value.video(), serverRoot));
        config.deathByEntity().forEach((key, value) -> addVideoEntry(entries, "death_by_entity", value.video(), serverRoot));
        config.advancementCompleted().forEach((key, value) -> addVideoEntry(entries, "advancement", value.video(), serverRoot));
        config.dimensionChanged().forEach((key, value) -> addVideoEntry(entries, "dimension", value.video(), serverRoot));
        return entries;
    }

    private static void addVideoEntry(List<VideoManifestEntry> entries, String conditionType, String configuredPath, Path root) {
        if (configuredPath == null || configuredPath.isBlank()) {
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
        ConditionalVideosConfig serverConfig = ConditionalVideosConfig.loadServer(player.server.getServerDirectory().toPath());
        List<VideoManifestEntry> entries = collectVideoEntries(serverConfig, player.server.getServerDirectory().toPath());
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

        byte[] all;
        try {
            all = Files.readAllBytes(source);
        } catch (IOException exception) {
            ConditionalVideos.LOGGER.warn("Failed reading configured video '{}'", configuredPath, exception);
            return;
        }

        int expectedChunks = (all.length + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE;
        FriendlyByteBuf start = new FriendlyByteBuf(Unpooled.buffer());
        start.writeUtf(configuredPath);
        start.writeUtf(targetEntry.conditionType());
        start.writeUtf(targetEntry.hash());
        start.writeUtf(targetEntry.extension());
        start.writeVarInt(expectedChunks);
        NetworkManager.sendToPlayer(player, SERVER_VIDEO_START_PACKET, start);

        for (int i = 0; i < expectedChunks; i++) {
            int startIndex = i * FILE_CHUNK_SIZE;
            int length = Math.min(FILE_CHUNK_SIZE, all.length - startIndex);
            FriendlyByteBuf chunk = new FriendlyByteBuf(Unpooled.buffer());
            chunk.writeUtf(configuredPath);
            chunk.writeVarInt(i);
            chunk.writeVarInt(length);
            chunk.writeBytes(all, startIndex, length);
            NetworkManager.sendToPlayer(player, SERVER_VIDEO_CHUNK_PACKET, chunk);
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