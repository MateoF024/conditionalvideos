package org.mateof24.conditionalvideos.runtime;

import org.mateof24.conditionalvideos.debug.ClientDebug;

public final class ClientLifecycle {
    private static final ClientRuntime CLIENT_RUNTIME = new ClientRuntime();

    private ClientLifecycle() {
    }

    public static void onClientTick() {
        ClientDebug.onClientTick();
        CLIENT_RUNTIME.onClientTick();
    }
}
