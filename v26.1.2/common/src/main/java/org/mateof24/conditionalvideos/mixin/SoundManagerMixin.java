package org.mateof24.conditionalvideos.mixin;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.mateof24.conditionalvideos.video.VideoAudioState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {

    // 1.21.11: SoundManager.play returns SoundEngine.PlayResult, so we short-circuit with NOT_STARTED
    // instead of a plain cancel to report the sound as not played.
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void conditionalvideos$blockPlayDuringVideo(CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (VideoAudioState.shouldMuteAudio()) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
    private void conditionalvideos$blockPlayDelayedDuringVideo(CallbackInfo ci) {
        if (VideoAudioState.shouldMuteAudio()) {
            ci.cancel();
        }
    }
}
