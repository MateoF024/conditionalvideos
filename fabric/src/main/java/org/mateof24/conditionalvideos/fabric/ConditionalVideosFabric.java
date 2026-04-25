package org.mateof24.conditionalvideos.fabric;

import org.mateof24.conditionalvideos.ConditionalVideos;
import net.fabricmc.api.ModInitializer;

public final class ConditionalVideosFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ConditionalVideos.init();
    }
}
