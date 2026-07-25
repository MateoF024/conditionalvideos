package org.mateof24.conditionalvideos.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// Minecraft's command encoder casts every drawn texture view to its own Vulkan type, so a foreign video
// frame cannot be wrapped in a subclass the way it is on OpenGL. Instead a real view is created and its
// handle is swapped for WaterMedia's; the original is restored before closing so Minecraft destroys the
// view it owns and never the one WaterMedia owns.
@Mixin(VulkanGpuTextureView.class)
public interface VulkanGpuTextureViewAccessor {

    @Accessor("vkImageView")
    long conditionalvideos$imageView();

    @Mutable
    @Accessor("vkImageView")
    void conditionalvideos$setImageView(long imageView);
}
