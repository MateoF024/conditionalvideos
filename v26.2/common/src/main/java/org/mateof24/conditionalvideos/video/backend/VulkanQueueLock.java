package org.mateof24.conditionalvideos.video.backend;

// A Vulkan queue may not be used from two threads at once (submits, presents and idle waits alike).
// WaterMedia decodes on its own thread and submits there, while Minecraft submits, presents and waits from
// the render thread, and both share the game's graphics queue: without a common lock the queue can stall
// or be lost and the game crashes. This is the object both sides synchronize on — WaterMedia through
// VKContext.queueLock().
public final class VulkanQueueLock {

    public static final Object INSTANCE = new Object();

    private VulkanQueueLock() {
    }
}
