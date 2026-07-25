package org.mateof24.conditionalvideos.video.backend;

// A Vulkan queue may not be submitted to from two threads at once. WaterMedia decodes on its own thread
// and submits there, while Minecraft submits from the render thread, and both share the game's graphics
// queue: without a common lock the driver eventually reports VK_ERROR_DEVICE_LOST and the game crashes.
// This is the object both sides synchronize on — WaterMedia through VKContext.queueLock().
public final class VulkanQueueLock {

    public static final Object INSTANCE = new Object();

    private VulkanQueueLock() {
    }
}
