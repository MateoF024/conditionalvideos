package org.mateof24.conditionalvideos.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;
import org.mateof24.conditionalvideos.runtime.ClientLifecycle;
import org.mateof24.conditionalvideos.runtime.ConditionalVideosKeybinds;

public final class ConditionalVideosFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ConditionalVideosKeybinds.register();
        ConfigSyncNetworking.initClient();
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientLifecycle.onClientTick());
    }
}
