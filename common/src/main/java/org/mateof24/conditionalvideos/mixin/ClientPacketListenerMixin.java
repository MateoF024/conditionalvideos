package org.mateof24.conditionalvideos.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.mateof24.conditionalvideos.condition.death.PlayerDeathVideoHandler;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import org.mateof24.conditionalvideos.condition.advancement.AdvancementVideoHandler;
import org.mateof24.conditionalvideos.condition.kill.KillEntityVideoHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handlePlayerCombatKill", at = @At("TAIL"))
    private void conditionalvideos$onPlayerCombatKill(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        if (packet.getPlayerId() == minecraft.player.getId()) {
            PlayerDeathVideoHandler.onPlayerDied(minecraft);
        }
    }

    @Inject(method = {"handleAwardStats", "handleAwardStatsPacket"}, at = @At("TAIL"), require = 0)
    private void conditionalvideos$onAwardStats(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        KillEntityVideoHandler.onAwardStatsPacketApplied(minecraft, packet);
    }

    @Inject(method = {"handleUpdateAdvancements", "handleUpdateAdvancementsPacket"}, at = @At("TAIL"), require = 0)
    private void conditionalvideos$onUpdateAdvancements(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        AdvancementVideoHandler.onAdvancementsPacketApplied(minecraft);
    }

}