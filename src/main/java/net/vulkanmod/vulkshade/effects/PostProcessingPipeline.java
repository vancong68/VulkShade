package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.lwjgl.vulkan.VK10.*;

public class PostProcessingPipeline {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-PostProcessing");

    private BloomEffect bloom;
    private MotionBlurEffect motionBlur;
    private LensFlareEffect lensFlare;

    private boolean initialized = false;
    private int width;
    private int height;

    private VulkanImage tempBuffer;

    public PostProcessingPipeline() {
    }

    public void initialize(int fbWidth, int fbHeight, VulkanImage sceneColorRef) {
        if (initialized && this.width == fbWidth && this.height == fbHeight) return;
        cleanup();
        this.width = fbWidth;
        this.height = fbHeight;

        int format = sceneColorRef != null ? sceneColorRef.format : VK_FORMAT_R16G16B16A16_SFLOAT;

        tempBuffer = VulkanImage.builder(fbWidth, fbHeight)
            .setName("PostProcess Temp")
            .setFormat(format)
            .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
            .setLinearFiltering(true)
            .setClamp(true)
            .createVulkanImage();

        bloom = new BloomEffect();
        bloom.initialize(fbWidth, fbHeight);

        motionBlur = new MotionBlurEffect();
        motionBlur.initialize(fbWidth, fbHeight);

        lensFlare = new LensFlareEffect();
        lensFlare.initialize(fbWidth, fbHeight);

        initialized = true;
        LOGGER.info("Post-processing pipeline initialized ({}x{})", fbWidth, fbHeight);
    }

    public void render(VkCommandBuffer cmdBuffer, VulkanImage sceneColor) {
        if (!initialized) return;

        if (bloom != null && bloom.isEnabled()) {
            transitionForRead(cmdBuffer, sceneColor);
            transitionForWrite(cmdBuffer, tempBuffer);
            bloom.render(cmdBuffer, sceneColor, tempBuffer);
            blitBack(cmdBuffer, tempBuffer, sceneColor);
        }

        if (lensFlare != null && lensFlare.isEnabled()) {
            transitionForRead(cmdBuffer, sceneColor);
            transitionForWrite(cmdBuffer, tempBuffer);
            lensFlare.render(cmdBuffer, sceneColor, tempBuffer);
            blitBack(cmdBuffer, tempBuffer, sceneColor);
        }

        if (motionBlur != null && motionBlur.isEnabled()) {
            transitionForRead(cmdBuffer, sceneColor);
            transitionForWrite(cmdBuffer, tempBuffer);
            motionBlur.render(cmdBuffer, sceneColor, tempBuffer);
            blitBack(cmdBuffer, tempBuffer, sceneColor);
        }
    }

    public void resize(int newWidth, int newHeight, VulkanImage sceneColorRef) {
        cleanup();
        initialize(newWidth, newHeight, sceneColorRef);
    }

    public void cleanup() {
        if (bloom != null) { bloom.cleanup(); bloom = null; }
        if (motionBlur != null) { motionBlur.cleanup(); motionBlur = null; }
        if (lensFlare != null) { lensFlare.cleanup(); lensFlare = null; }
        if (tempBuffer != null) { tempBuffer.free(); tempBuffer = null; }
        initialized = false;
    }

    public BloomEffect getBloom() { return bloom; }
    public MotionBlurEffect getMotionBlur() { return motionBlur; }
    public LensFlareEffect getLensFlare() { return lensFlare; }
    public boolean isInitialized() { return initialized; }

    private void transitionForRead(VkCommandBuffer cmd, VulkanImage img) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            img.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    private void transitionForWrite(VkCommandBuffer cmd, VulkanImage img) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            img.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_GENERAL);
        }
    }

    private void blitBack(VkCommandBuffer cmd, VulkanImage src, VulkanImage dst) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            src.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dst.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            VkImageCopy.Buffer copyBuf = VkImageCopy.calloc(1, stack);
            copyBuf.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copyBuf.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copyBuf.extent().width(Math.min(src.width, dst.width));
            copyBuf.extent().height(Math.min(src.height, dst.height));
            copyBuf.extent().depth(1);
            vkCmdCopyImage(cmd, src.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dst.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copyBuf);

            dst.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }
}
