package org.mateof24.conditionalvideos.network;

import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;

public final class ConfigSyncNetworking {
    private static final ResourceLocation SERVER_CONFIG_PACKET =
            new ResourceLocation(ConditionalVideos.MOD_ID, "server_config_sync");

    private ConfigSyncNetworking() {
    }

    public static void init() {
        if (Platform.getEnvironment() == Env.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.s2c(), SERVER_CONFIG_PACKET, (buffer, context) -> {
                String json = buffer.readUtf(262144);
                context.queue(() -> ActiveConfigResolver.setRemoteConfig(ConditionalVideosConfig.fromJson(json)));
            });
        }

        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (!(player instanceof ServerPlayer)) {
                return;
            }
            sendServerConfig((ServerPlayer) player);
        });
    }

    public static void sendServerConfig(ServerPlayer player) {
        if (!NetworkManager.canPlayerReceive(player, SERVER_CONFIG_PACKET)) {
            return;
        }
        ConditionalVideosConfig serverConfig = ConditionalVideosConfig.loadServer(player.serverLevel().getServer().getServerDirectory().toPath());
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeUtf(serverConfig.toJson());
        NetworkManager.sendToPlayer(player, SERVER_CONFIG_PACKET, buffer);
    }

    public static void onClientDisconnected() {
        ActiveConfigResolver.resetRemoteSessionState();
    }
}