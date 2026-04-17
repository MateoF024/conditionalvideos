package org.mateof24.conditionalvideos;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.nio.file.Path;

final class SessionKeyResolver {
    private SessionKeyResolver() {
    }

    static String resolveSessionKey(Minecraft minecraft) {
        ServerData currentServer = minecraft.getCurrentServer();
        if (currentServer != null && currentServer.ip != null && !currentServer.ip.isBlank()) {
            return "server:" + currentServer.ip.toLowerCase();
        }

        Path levelPath = minecraft.gameDirectory.toPath().resolve("saves").resolve(minecraft.getSingleplayerServer() != null
                ? minecraft.getSingleplayerServer().getWorldData().getLevelName()
                : "unknown");
        return "world:" + levelPath.normalize();
    }
}