package org.mateof24.conditionalvideos.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// GpuDevice keeps its backend private, but the Vulkan path needs it: that backend is the VulkanDevice
// carrying the VKContext implementation handed to WaterMedia.
@Mixin(GpuDevice.class)
public interface GpuDeviceAccessor {

    @Accessor("backend")
    GpuDeviceBackend conditionalvideos$backend();
}
