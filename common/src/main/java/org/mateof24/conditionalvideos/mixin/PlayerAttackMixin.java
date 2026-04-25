package org.mateof24.conditionalvideos.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.mateof24.conditionalvideos.condition.kill.KillEntityVideoHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void conditionalvideos$onAttack(Entity target, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer localPlayer)) {
            return;
        }
        if (Minecraft.getInstance().player != localPlayer) {
            return;
        }
        if (!(target instanceof LivingEntity living)) {
            return;
        }
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
        if (key != null) {
            KillEntityVideoHandler.onPlayerAttackedEntity(living.getId(), key.toString());
        }
    }
}