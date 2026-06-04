package org.mateof24.conditionalvideos.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class TotemUsedMixin {

    @Inject(method = "checkTotemDeathProtection", at = @At("RETURN"))
    private void conditionalvideos$onTotemUsed(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        if ((Object) this instanceof ServerPlayer player) {
            ServerConditionDispatcher.fire(player, ConditionRegistry.KEY_TOTEM_USED);
        }
    }
}
