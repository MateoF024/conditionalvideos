package org.mateof24.conditionalvideos.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.runtime.PlaybackControlClient;

import java.util.Map;

// Server -> client playback control (play / stop / pause). On v1.21.1 every payload travels through
// the single RawS2C type, so these S2C handlers are MERGED into ConfigSyncNetworking's handler map
// (registering the global receiver twice would crash); this class only contributes handlers + senders.
public final class PlaybackControlNetworking {
    private static final ResourceLocation CONTROL_PLAY =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "control_play");
    private static final ResourceLocation CONTROL_STOP =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "control_stop");
    private static final ResourceLocation CONTROL_PAUSE =
            ResourceLocation.fromNamespaceAndPath(ConditionalVideos.MOD_ID, "control_pause");

    private PlaybackControlNetworking() {
    }

    public static void registerS2CHandlers(Map<ResourceLocation, NetworkHelper.S2CPacketHandler> s2cHandlers) {
        s2cHandlers.put(CONTROL_PLAY, data -> {
            FriendlyByteBuf buf = NetworkHelper.fromBytes(data);
            String conditionKey = buf.readUtf();
            PlaybackControlClient.play(conditionKey);
        });
        s2cHandlers.put(CONTROL_STOP, data -> PlaybackControlClient.stop());
        s2cHandlers.put(CONTROL_PAUSE, data -> PlaybackControlClient.togglePause());
    }

    public static void sendPlay(ServerPlayer player, String conditionKey) {
        NetworkHelper.sendToPlayer(player, CONTROL_PLAY,
                NetworkHelper.toBytes(buf -> buf.writeUtf(conditionKey)));
    }

    public static void sendStop(ServerPlayer player) {
        NetworkHelper.sendToPlayer(player, CONTROL_STOP, new byte[0]);
    }

    public static void sendPause(ServerPlayer player) {
        NetworkHelper.sendToPlayer(player, CONTROL_PAUSE, new byte[0]);
    }
}
