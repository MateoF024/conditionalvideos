package org.mateof24.conditionalvideos;

import net.minecraft.client.Minecraft;

final class ClientRuntime {
    private boolean wasInSession;

    void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            wasInSession = false;
            return;
        }

        if (!wasInSession) {
            wasInSession = true;
            JoinVideoHandler.onJoinedSession(minecraft);
        }
    }
}