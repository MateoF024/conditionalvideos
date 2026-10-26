package org.mateof24.conditionalvideos.mixin;

import com.mojang.blaze3d.vulkan.VulkanQueue;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkQueue;
import org.mateof24.conditionalvideos.video.backend.VulkanQueueLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Waiting for the graphics queue to go idle is a use of the queue that Vulkan requires to be externally
// synchronized as well, so it takes the queue lock WaterMedia submits under.
@Mixin(VulkanQueue.class)
public class VulkanQueueMixin {

    @Redirect(
            method = "waitIdle",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK12;vkQueueWaitIdle(Lorg/lwjgl/vulkan/VkQueue;)I"))
    private int conditionalvideos$waitIdleExclusively(VkQueue queue) {
        synchronized (VulkanQueueLock.INSTANCE) {
            return VK12.vkQueueWaitIdle(queue);
        }
    }
}
