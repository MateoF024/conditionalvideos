package org.mateof24.conditionalvideos.runtime;

import org.mateof24.conditionalvideos.debug.ClientDebug;

// Loaded only from client entry points (Fabric client initializer, Forge client-side setup).
// Keeping ClientRuntime out of ConditionalVideos prevents the dedicated server from linking
// Minecraft client classes (and transitively WaterMedia) via static initialization.
public final class ClientLifecycle {
    private static final ClientRuntime CLIENT_RUNTIME = new ClientRuntime();

    private ClientLifecycle() {
    }

    public static void onClientTick() {
        ClientDebug.onClientTick();
        CLIENT_RUNTIME.onClientTick();
    }
}
