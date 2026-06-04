package org.mateof24.conditionalvideos.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

@Mixin(ServerPlayer.class)
public abstract class RecipeUnlockedMixin {

    // 1.21.1: awardRecipes takes Collection<RecipeHolder<?>> and the id lives on RecipeHolder.id().
    @Inject(method = "awardRecipes", at = @At("HEAD"))
    private void conditionalvideos$onAwardRecipes(Collection<RecipeHolder<?>> recipes, CallbackInfoReturnable<Integer> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        for (RecipeHolder<?> recipe : recipes) {
            if (recipe == null || self.getRecipeBook().contains(recipe)) {
                continue;
            }
            ResourceLocation id = recipe.id();
            if (id != null) {
                ServerConditionDispatcher.fire(self, ConditionRegistry.TYPE_RECIPE_UNLOCKED + "/" + id);
            }
        }
    }
}
