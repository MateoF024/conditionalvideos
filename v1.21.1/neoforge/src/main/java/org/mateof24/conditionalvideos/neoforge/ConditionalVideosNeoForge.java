package org.mateof24.conditionalvideos.neoforge;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;
import org.mateof24.conditionalvideos.runtime.ClientLifecycle;
import org.mateof24.conditionalvideos.runtime.ConditionalVideosKeybinds;

@Mod(ConditionalVideos.MOD_ID)
public final class ConditionalVideosNeoForge {

    public ConditionalVideosNeoForge() {
        ConditionalVideos.init();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ConditionalVideosKeybinds.register();
            ConfigSyncNetworking.initClient();
            NeoForge.EVENT_BUS.addListener(this::onClientTick);
        }
    }

    private void onClientTick(ClientTickEvent.Post event) {
        ClientLifecycle.onClientTick();
    }
}
