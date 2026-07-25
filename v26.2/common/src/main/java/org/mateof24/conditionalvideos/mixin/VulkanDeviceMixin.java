package org.mateof24.conditionalvideos.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkQueue;
import org.mateof24.conditionalvideos.video.backend.VulkanQueueLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.watermedia.api.media.engines.vk.VKContext;

// Implements WaterMedia's VKContext directly on Minecraft's Vulkan device, which is the integration
// pattern WaterMedia documents: the game's own vkDevice() already satisfies the interface verbatim, so
// the video engine can share the device, queue and memory the game is already using.
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin implements VKContext {

    @Shadow public abstract VulkanInstance instance();

    @Shadow public abstract VulkanQueue graphicsQueue();

    @Shadow public abstract VkDevice vkDevice();

    @Unique
    private VkPhysicalDeviceMemoryProperties conditionalvideos$memoryProperties;

    @Override
    public VkInstance vkInstance() {
        return this.instance().vkInstance();
    }

    @Override
    public VkPhysicalDevice physicalDevice() {
        return this.vkDevice().getPhysicalDevice();
    }

    @Override
    public VkQueue queue() {
        return this.graphicsQueue().vkQueue();
    }

    @Override
    public int queueFamily() {
        return this.graphicsQueue().queueFamilyIndex();
    }

    // Both sides must serialize on the SAME object: Minecraft's own submissions are routed through this
    // lock by VulkanQueueSubmissionMixin, so WaterMedia never submits to the queue at the same time.
    @Override
    public Object queueLock() {
        return VulkanQueueLock.INSTANCE;
    }

    @Override
    public VkPhysicalDeviceMemoryProperties memoryProperties() {
        if (this.conditionalvideos$memoryProperties == null) {
            this.conditionalvideos$memoryProperties = VkPhysicalDeviceMemoryProperties.calloc();
            VK10.vkGetPhysicalDeviceMemoryProperties(this.physicalDevice(), this.conditionalvideos$memoryProperties);
        }
        return this.conditionalvideos$memoryProperties;
    }

    // Host-pointer import and YCbCr sampling are left off, so WaterMedia uploads through its own staging
    // buffers and converts planes itself, which is the conservative path of the documented pattern.
    @Override
    public boolean hostImportSupported() {
        return false;
    }

    @Override
    public long minImportedHostPointerAlignment() {
        return 0L;
    }

    @Override
    public boolean ycbcrSampler() {
        return false;
    }

    // Deferred destruction: the resource is only freed once the frames still using it have been drawn.
    @Override
    public void retire(Runnable destroy) {
        RenderSystem.queueFencedTask(destroy);
    }
}
