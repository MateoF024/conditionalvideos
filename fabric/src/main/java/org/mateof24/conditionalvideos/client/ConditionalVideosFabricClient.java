package org.mateof24.conditionalvideos.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;

public final class ConditionalVideosFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ConfigSyncNetworking.initClient();
        ClientTickEvents.END_CLIENT_TICK.register(client -> ConditionalVideos.onClientTick());
    }
}
