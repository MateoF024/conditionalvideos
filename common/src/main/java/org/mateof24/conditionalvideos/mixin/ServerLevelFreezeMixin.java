package org.mateof24.conditionalvideos.mixin;

import net.minecraft.server.level.ServerLevel;
import org.mateof24.conditionalvideos.world.WorldFreeze;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

// Skips the world tick while WorldFreeze holds it; the server keeps handling the network.
@Mixin(ServerLevel.class)
public abstract class ServerLevelFreezeMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void conditionalvideos$freeze(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (WorldFreeze.isFrozen()) {
            ci.cancel();
        }
    }
}
