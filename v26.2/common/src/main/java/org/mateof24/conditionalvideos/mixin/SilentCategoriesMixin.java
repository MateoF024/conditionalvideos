package org.mateof24.conditionalvideos.mixin;

import net.minecraft.client.Options;
import net.minecraft.sounds.SoundSource;
import org.mateof24.conditionalvideos.video.VideoAudioState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// While game sounds are silenced for a video, every sound category except master reads as zero, so mods
// that play audio on their own but honour the sliders (Reactive Music, MuSync, voice chats) go quiet too.
// Only the value read changes: the sliders and options.txt keep what the player set.
@Mixin(Options.class)
public abstract class SilentCategoriesMixin {

    @Inject(method = "getSoundSourceVolume", at = @At("HEAD"), cancellable = true)
    private void conditionalvideos$silence(SoundSource source, CallbackInfoReturnable<Float> cir) {
        if (source != SoundSource.MASTER && VideoAudioState.shouldMuteAudio()) {
            cir.setReturnValue(0f);
        }
    }

    @Inject(method = "getFinalSoundSourceVolume", at = @At("HEAD"), cancellable = true)
    private void conditionalvideos$silenceFinal(SoundSource source, CallbackInfoReturnable<Float> cir) {
        if (source != SoundSource.MASTER && VideoAudioState.shouldMuteAudio()) {
            cir.setReturnValue(0f);
        }
    }
}
