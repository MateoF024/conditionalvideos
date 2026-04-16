package org.mateof24.conditionalvideos.forge;

import org.mateof24.conditionalvideos.conditionalvideos;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(conditionalvideos.MOD_ID)
public final class conditionalvideosForge {
    public conditionalvideosForge() {
        EventBuses.registerModEventBus(conditionalvideos.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());

        conditionalvideos.init();
    }
}
