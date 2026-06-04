package org.mateof24.conditionalvideos.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.screens.Screen;
import org.mateof24.conditionalvideos.video.VideoLoadingScreen;
import org.mateof24.conditionalvideos.video.VideoPlaybackScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ToastComponent.class)
public abstract class ToastComponentMixin {

    // Toasts are drawn after the active screen, so they appear on top of the video and obstruct it.
    // While a video screen is showing we skip rendering them entirely; the toasts stay queued and
    // are shown once playback ends (postponed, not lost).
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void conditionalvideos$suppressToastsDuringVideo(GuiGraphics guiGraphics, CallbackInfo ci) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof VideoPlaybackScreen || screen instanceof VideoLoadingScreen) {
            ci.cancel();
        }
    }
}
