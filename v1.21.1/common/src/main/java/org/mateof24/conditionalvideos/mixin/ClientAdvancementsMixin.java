package org.mateof24.conditionalvideos.mixin;

import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import org.mateof24.conditionalvideos.condition.advancement.AdvancementVideoHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientAdvancements.class)
public abstract class ClientAdvancementsMixin {

    @Inject(method = "update", at = @At("TAIL"))
    private void conditionalvideos$onAdvancementsUpdate(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        AdvancementVideoHandler.onPacketReceived(
                packet.shouldReset(),
                packet.getRemoved(),
                packet.getProgress()
        );
    }
}
