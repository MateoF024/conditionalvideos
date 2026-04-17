package org.mateof24.conditionalvideos.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.mateof24.conditionalvideos.ConditionalVideos;

public final class conditionalvideosFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> ConditionalVideos.onClientTick());
    }
}
