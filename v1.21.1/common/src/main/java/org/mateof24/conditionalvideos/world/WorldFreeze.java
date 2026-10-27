package org.mateof24.conditionalvideos.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import org.mateof24.conditionalvideos.debug.DebugLog;

// Stops the world while every connected player watches a freezeWorld video, with the native tick freeze
// (the one of /tick freeze). A freeze an admin had already set is left as it is when the videos end.
public final class WorldFreeze {
    private static boolean wanted;
    private static boolean ours;

    private WorldFreeze() {
    }

    public static boolean isFrozen() {
        return ours;
    }

    public static void set(MinecraftServer server, boolean value) {
        if (wanted == value) {
            return;
        }
        wanted = value;
        ServerTickRateManager manager = server.tickRateManager();
        if (value) {
            if (manager.isFrozen()) {
                DebugLog.server(DebugLog.Area.FREEZE, "Every connected player is watching a freezeWorld video, but an admin had already frozen the world; left as it is.");
                return;
            }
            manager.setFrozen(true);
            ours = true;
            DebugLog.server(DebugLog.Area.FREEZE, "Froze the world: every connected player is watching a freezeWorld video.");
        } else if (ours) {
            ours = false;
            manager.setFrozen(false);
            DebugLog.server(DebugLog.Area.FREEZE, "Unfroze the world.");
        }
    }

    public static void reset(MinecraftServer server) {
        if (ours) {
            server.tickRateManager().setFrozen(false);
        }
        ours = false;
        wanted = false;
    }
}
