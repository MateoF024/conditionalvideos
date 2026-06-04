package org.mateof24.conditionalvideos.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import org.mateof24.conditionalvideos.condition.server.ServerConditionDispatcher;
import org.mateof24.conditionalvideos.condition.shared.ConditionRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

@Mixin(ServerPlayer.class)
public abstract class RecipeUnlockedMixin {

    @Inject(method = "awardRecipes", at = @At("HEAD"))
    private void conditionalvideos$onAwardRecipes(Collection<Recipe<?>> recipes, CallbackInfoReturnable<Integer> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        for (Recipe<?> recipe : recipes) {
            if (recipe == null || self.getRecipeBook().contains(recipe)) {
                continue;
            }
            ResourceLocation id = recipe.getId();
            if (id != null) {
                ServerConditionDispatcher.fire(self, ConditionRegistry.TYPE_RECIPE_UNLOCKED + "/" + id);
            }
        }
    }
}
