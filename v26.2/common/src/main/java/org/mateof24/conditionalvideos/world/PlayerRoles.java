package org.mateof24.conditionalvideos.world;

import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

// Who a player is for ConditionalVideos: the host of a singleplayer or LAN world, or an operator
// (permission level 2 or higher).
public final class PlayerRoles {
    private PlayerRoles() {
    }

    public static boolean isHost(MinecraftServer server, ServerPlayer player) {
        return !server.isDedicatedServer() && server.isSingleplayerOwner(player.nameAndId());
    }

    public static boolean isOperator(ServerPlayer player) {
        return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }
}
