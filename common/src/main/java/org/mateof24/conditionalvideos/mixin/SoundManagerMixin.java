package org.mateof24.conditionalvideos.mixin;

import net.minecraft.client.sounds.SoundManager;
import org.mateof24.conditionalvideos.video.VideoAudioState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void conditionalvideos$blockPlayDuringVideo(CallbackInfo ci) {
        if (VideoAudioState.isVideoPlaying()) {
            ci.cancel();
        }
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
    private void conditionalvideos$blockPlayDelayedDuringVideo(CallbackInfo ci) {
        if (VideoAudioState.isVideoPlaying()) {
            ci.cancel();
        }
    }
}