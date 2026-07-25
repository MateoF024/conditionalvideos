package org.mateof24.conditionalvideos.mixin;

import net.minecraft.advancements.criterion.UsedTotemTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.11 inlined the totem death-protection check into the damage pipeline, removing the old
// checkTotemDeathProtection hook. We instead hook the totem advancement trigger, which fires exactly
// when a totem of undying saves a server player and hands us that player directly.
@Mixin(UsedTotemTrigger.class)
public abstract class TotemUsedMixin {

    @Inject(method = "trigger", at = @At("HEAD"))
    private void conditionalvideos$onTotemUsed(ServerPlayer player, ItemStack stack, CallbackInfo ci) {
        ServerConditionDispatcher.fire(player, ConditionRegistry.KEY_TOTEM_USED);
    }
}
