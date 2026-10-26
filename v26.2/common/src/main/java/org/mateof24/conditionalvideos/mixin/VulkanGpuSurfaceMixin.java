package org.mateof24.conditionalvideos.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.mateof24.conditionalvideos.video.backend.VulkanQueueLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Minecraft presents on its graphics queue, the same queue WaterMedia submits decoded frames to from its
// decoding thread. Vulkan requires presents to be externally synchronized with submits, so they take the
// shared queue lock too; a present racing a submit can stall the queue until the game crashes.
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceMixin {

    @Redirect(
            method = "present",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
    private int conditionalvideos$presentExclusively(VkQueue queue, VkPresentInfoKHR presentInfo) {
        synchronized (VulkanQueueLock.INSTANCE) {
            return KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
        }
    }
}
