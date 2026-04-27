package org.mateof24.conditionalvideos.network.neoforge;

import dev.architectury.networking.NetworkManager;
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
        NetworkManager.registerReceiver(NetworkManager.s2c(), NetworkHelper.RawS2C.TYPE, NetworkHelper.RawS2C.CODEC,
                (payload, context) -> {
                    NetworkHelper.S2CPacketHandler handler = s2cHandlers.get(payload.id());
                    if (handler != null) {
                        context.queue(() -> handler.handle(payload.data()));
                    }
                });

        NetworkManager.registerReceiver(NetworkManager.c2s(), NetworkHelper.RawC2S.TYPE, NetworkHelper.RawC2S.CODEC,
                (payload, context) -> {
                    NetworkHelper.C2SPacketHandler handler = c2sHandlers.get(payload.id());
                    if (handler != null && context.getPlayer() instanceof ServerPlayer player) {
                        context.queue(() -> handler.handle(payload.data(), player));
                    }
                });
    }

    public static void registerS2CClientHandlers(Map<ResourceLocation, NetworkHelper.S2CPacketHandler> s2cHandlers) {
    }

    public static void sendToPlayer(ServerPlayer player, ResourceLocation id, byte[] data) {
        NetworkManager.sendToPlayer(player, new NetworkHelper.RawS2C(id, data));
    }

    public static void sendToServer(ResourceLocation id, byte[] data) {
        NetworkManager.sendToServer(new NetworkHelper.RawC2S(id, data));
    }
}