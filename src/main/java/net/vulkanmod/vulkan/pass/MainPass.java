package net.vulkanmod.vulkan.pass;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

public interface MainPass {

    void begin(VkCommandBuffer commandBuffer, MemoryStack stack);

    void end(VkCommandBuffer commandBuffer);

    void cleanUp();

    void onResize();

    default void mainTargetBindWrite() {}

    default void mainTargetUnbindWrite() {}

    default void rebindMainTarget() {}

    default void bindAsTexture() {}

    default GpuTexture getColorAttachment() {
        return null;
    }

    default GpuTextureView getColorAttachmentView() {
        return null;
    }

    default GpuTexture getDepthAttachment() {
        return null;
    }

    default VulkanImage getColorAttachmentImage() {
        return null;
    }

    default VulkanImage getDepthAttachmentImage() {
        return null;
    }

    default int getRenderWidth() {
        return 0;
    }

    default int getRenderHeight() {
        return 0;
    }

    default int getSceneRenderWidth() {
        return getRenderWidth();
    }

    default int getSceneRenderHeight() {
        return getRenderHeight();
    }

    default int getOutputWidth() {
        return getRenderWidth();
    }

    default int getOutputHeight() {
        return getRenderHeight();
    }

    default boolean isFsrEnabled() {
        return false;
    }

    default void resolveForGui(VkCommandBuffer commandBuffer) {}

}
