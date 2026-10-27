package org.mateof24.conditionalvideos.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.mateof24.conditionalvideos.world.PlaybackSessions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A protected player picks nothing up (items, experience, arrows), like a spectator.
@Mixin(Player.class)
public abstract class ProtectedTouchMixin {

    @Inject(method = "touch", at = @At("HEAD"), cancellable = true)
    private void conditionalvideos$skipWhileProtected(Entity entity, CallbackInfo ci) {
        if (PlaybackSessions.isImmune((Player) (Object) this)) {
            ci.cancel();
        }
    }
}
