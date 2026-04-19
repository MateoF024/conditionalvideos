package org.mateof24.conditionalvideos.runtime;

import net.minecraft.client.Minecraft;
import org.mateof24.conditionalvideos.condition.join.JoinVideoHandler;

public final class ClientRuntime {
    private boolean wasInSession;

    public void onClientTick() {
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