package org.mateof24.conditionalvideos.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.mateof24.conditionalvideos.runtime.ClientLifecycle;
import org.mateof24.conditionalvideos.runtime.ConditionalVideosKeybinds;

public final class conditionalvideosFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ConditionalVideosKeybinds.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientLifecycle.onClientTick());
    }
}
