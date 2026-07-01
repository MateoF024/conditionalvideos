package org.mateof24.conditionalvideos.runtime;

import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.minecraft.client.KeyMapping;

// Client-only. Referenced solely from the Fabric/NeoForge client entry points, so the dedicated
// server never links Minecraft client classes through this path.
public final class ConditionalVideosKeybinds {
    // 1.21.11 replaced the String category with a KeyMapping.Category; we reuse the built-in GAMEPLAY
    // category so the label is always translated correctly without registering a custom one.
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.GAMEPLAY;

    // Default unbound: out of the box, skipping falls back to ESC (tap = skip, hold = close)
    // handled directly by VideoPlaybackScreen. Binding this to another key splits skip (that key)
    // from close (ESC).
    public static final KeyMapping SKIP = new KeyMapping(
            "key.conditionalvideos.skip",
            InputConstants.UNKNOWN.getValue(),
            CATEGORY
    );

    private ConditionalVideosKeybinds() {
    }

    public static void register() {
        KeyMappingRegistry.register(SKIP);
    }
}
