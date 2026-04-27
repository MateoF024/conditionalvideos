package org.mateof24.conditionalvideos;

import org.mateof24.conditionalvideos.ConditionalVideos;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.ModList;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.mateof24.conditionalvideos.network.ConfigSyncNetworking;

@Mod(ConditionalVideos.MOD_ID)
public final class ConditionalVideosNeoForge {

    public ConditionalVideosNeoForge() {
        if (FMLEnvironment.dist == Dist.CLIENT && !ModList.get().isLoaded("watermedia")) {
            throw new IllegalStateException(
                    "ConditionalVideos requires WaterMedia API on client installations. Please install watermedia ~2.1.37."
            );
        }

        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        ConditionalVideos.init();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ConfigSyncNetworking.initClient();
        }
    }

    private void onClientTick(ClientTickEvent.Post event) {
        ConditionalVideos.onClientTick();
    }
}