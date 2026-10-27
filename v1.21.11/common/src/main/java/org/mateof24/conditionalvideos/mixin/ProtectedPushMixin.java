package org.mateof24.conditionalvideos.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.mateof24.conditionalvideos.world.PlaybackSessions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Entities do not push a protected player, like a spectator. The player's own client applies it to
// itself, since that is where its movement is simulated.
@Mixin(LivingEntity.class)
public abstract class ProtectedPushMixin {

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void conditionalvideos$notPushableWhileProtected(CallbackInfoReturnable<Boolean> cir) {
        if (PlaybackSessions.isImmune((LivingEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
