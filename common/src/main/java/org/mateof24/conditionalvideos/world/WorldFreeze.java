package org.mateof24.conditionalvideos.world;

import net.minecraft.server.MinecraftServer;
import org.mateof24.conditionalvideos.debug.DebugLog;

// Stops the world while every connected player watches a freezeWorld video. This version has no tick
// freeze, so the level ticks are skipped (ServerLevelFreezeMixin) while the network keeps running, the
// same effect as the singleplayer pause.
public final class WorldFreeze {
    private static volatile boolean frozen;

    private WorldFreeze() {
    }

    public static boolean isFrozen() {
        return frozen;
    }

    public static void set(MinecraftServer server, boolean value) {
        if (frozen == value) {
            return;
        }
        frozen = value;
        DebugLog.server(DebugLog.Area.FREEZE, value
                ? "Froze the world: every connected player is watching a freezeWorld video."
                : "Unfroze the world.");
    }

    public static void reset(MinecraftServer server) {
        frozen = false;
    }
}
