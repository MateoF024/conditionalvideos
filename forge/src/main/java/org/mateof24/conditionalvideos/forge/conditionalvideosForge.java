package org.mateof24.conditionalvideos.forge;

import org.mateof24.conditionalvideos.ConditionalVideos;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

@Mod(ConditionalVideos.MOD_ID)
public final class conditionalvideosForge {
    public conditionalvideosForge() {
        EventBuses.registerModEventBus(ConditionalVideos.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
        ConditionalVideos.init();
    }
    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ConditionalVideos.onClientTick();
        }

    }
}
