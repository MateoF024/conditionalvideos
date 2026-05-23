package org.mateof24.conditionalvideos.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.mateof24.conditionalvideos.runtime.ClientLifecycle;

// Referenced only inside the FMLEnvironment.dist == Dist.CLIENT branch of the mod constructor,
// so the dedicated server never links Minecraft/WaterMedia-touching classes via this path.
public final class ClientSideSetup {
    private ClientSideSetup() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(ClientSideSetup::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ClientLifecycle.onClientTick();
        }
    }
}
