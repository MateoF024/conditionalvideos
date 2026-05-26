package org.mateof24.conditionalvideos.runtime;

public final class ClientLifecycle {
    private static final ClientRuntime CLIENT_RUNTIME = new ClientRuntime();

    private ClientLifecycle() {
    }

    public static void onClientTick() {
        CLIENT_RUNTIME.onClientTick();
    }
}
