package org.mateof24.conditionalvideos.mixin;

import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.mateof24.conditionalvideos.video.backend.VulkanQueueLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Minecraft submits to the Vulkan queue without locking, which is fine on its own but not while
// WaterMedia submits decoded frames to that same queue from its decoding thread. Both submissions are
// routed through one lock so they never overlap.
@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanQueue$Submission")
public class VulkanQueueSubmissionMixin {

    @Redirect(
            method = "close",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
    private int conditionalvideos$submitExclusively(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence) {
        synchronized (VulkanQueueLock.INSTANCE) {
            return KHRSynchronization2.vkQueueSubmit2KHR(queue, submits, fence);
        }
    }
}
