package org.mateof24.conditionalvideos.network.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.network.NetworkHelper;

import java.util.Map;

public final class NetworkHelperImpl {
    private NetworkHelperImpl() {
    }

    public static void registerPackets(
            Map<ResourceLocation, NetworkHelper.S2CPacketHandler> s2cHandlers,
            Map<ResourceLocation, NetworkHelper.C2SPacketHandler> c2sHandlers) {
        PayloadTypeRegistry.playS2C().register(NetworkHelper.RawS2C.TYPE, NetworkHelper.RawS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(NetworkHelper.RawC2S.TYPE, NetworkHelper.RawC2S.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(NetworkHelper.RawC2S.TYPE, (payload, context) -> {
            NetworkHelper.C2SPacketHandler handler = c2sHandlers.get(payload.id());
            if (handler != null) {
                ServerPlayer player = context.player();
                context.server().execute(() -> handler.handle(payload.data(), player));
            }
        });
    }

    public static void registerS2CClientHandlers(Map<ResourceLocation, NetworkHelper.S2CPacketHandler> s2cHandlers) {
        ClientPlayNetworking.registerGlobalReceiver(NetworkHelper.RawS2C.TYPE, (payload, context) -> {
            NetworkHelper.S2CPacketHandler handler = s2cHandlers.get(payload.id());
            if (handler != null) {
                context.client().execute(() -> handler.handle(payload.data()));
            }
        });
    }

    public static void sendToPlayer(ServerPlayer player, ResourceLocation id, byte[] data) {
        ServerPlayNetworking.send(player, new NetworkHelper.RawS2C(id, data));
    }

    public static void sendToServer(ResourceLocation id, byte[] data) {
        ClientPlayNetworking.send(new NetworkHelper.RawC2S(id, data));
    }
}