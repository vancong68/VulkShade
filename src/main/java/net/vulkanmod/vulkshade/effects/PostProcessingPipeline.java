package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.config.VulkShadeConfig;
import net.vulkanmod.vulkshade.optimization.ShaderVariantSystem;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.VkCommandBuffer;
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
    private float lastMotionBlurStrength = -1f;

    public PostProcessingPipeline() {
    }

    public void initialize(int fbWidth, int fbHeight, VulkanImage sceneColorRef) {
        if (initialized && this.width == fbWidth && this.height == fbHeight) return;
        cleanup();
        this.width = fbWidth;
        this.height = fbHeight;

        tempBuffer = VulkanImage.builder(fbWidth, fbHeight)
            .setName("PostProcess Temp")
            .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
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

    public void render(VkCommandBuffer cmdBuffer, VulkanImage sceneColor, VulkanImage depthBuffer) {
        if (!initialized) return;
        if (sceneColor == null) {
            LOGGER.warn("Post-processing skipped: null scene color");
            return;
        }
        if (tempBuffer == null) {
            LOGGER.warn("Post-processing skipped: null temp buffer");
            return;
        }

        syncWithConfig();

        int effectsRun = 0;

        if (bloom != null && bloom.isEnabled()) {
            boolean ok = false;
            try {
                transitionForRead(cmdBuffer, sceneColor);
                transitionForWrite(cmdBuffer, tempBuffer);
                clearImageToBlack(cmdBuffer, tempBuffer);
                bloom.render(cmdBuffer, sceneColor, tempBuffer);
                VulkanImage bloomOutput = bloom.getOutputImage();
                if (bloomOutput != null) {
                    blitBack(cmdBuffer, bloomOutput, sceneColor);
                }
                ok = true;
                effectsRun++;
            } catch (Exception e) {
                LOGGER.error("Bloom effect failed, disabling permanently", e);
                bloom.setEnabled(false);
            }
            if (!ok) {
                recoverLayouts(cmdBuffer, sceneColor);
            }
            effectBarrier(cmdBuffer);
        }

        if (lensFlare != null && lensFlare.isEnabled()) {
            boolean ok = false;
            try {
                transitionForRead(cmdBuffer, sceneColor);
                transitionForWrite(cmdBuffer, tempBuffer);
                clearImageToBlack(cmdBuffer, tempBuffer);
                lensFlare.render(cmdBuffer, sceneColor, tempBuffer);
                blitBack(cmdBuffer, tempBuffer, sceneColor);
                ok = true;
                effectsRun++;
            } catch (Exception e) {
                LOGGER.error("Lens flare effect failed, disabling permanently", e);
                lensFlare.setEnabled(false);
            }
            if (!ok) {
                recoverLayouts(cmdBuffer, sceneColor);
            }
            effectBarrier(cmdBuffer);
        }

        if (motionBlur != null && motionBlur.isEnabled()) {
            boolean ok = false;
            try {
                transitionForRead(cmdBuffer, sceneColor);
                transitionForWrite(cmdBuffer, tempBuffer);
                clearImageToBlack(cmdBuffer, tempBuffer);
                motionBlur.render(cmdBuffer, sceneColor, tempBuffer);
                blitBack(cmdBuffer, tempBuffer, sceneColor);
                ok = true;
                effectsRun++;
            } catch (Exception e) {
                LOGGER.error("Motion blur effect failed, disabling permanently", e);
                motionBlur.setEnabled(false);
            }
            if (!ok) {
                recoverLayouts(cmdBuffer, sceneColor);
            }
        }

        if (effectsRun > 0 && LOGGER.isDebugEnabled()) {
            LOGGER.debug("Post-processing ran {} effects this frame", effectsRun);
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

    private void syncWithConfig() {
        try {
            ShaderVariantSystem svs = ShaderVariantSystem.getInstance();
            if (bloom != null) bloom.setEnabled(svs.isFeatureEnabled(ShaderVariantSystem.ShaderFeature.BLOOM));
            if (lensFlare != null) lensFlare.setEnabled(svs.isFeatureEnabled(ShaderVariantSystem.ShaderFeature.LENS_FLARE));
            if (motionBlur != null) motionBlur.setEnabled(svs.isFeatureEnabled(ShaderVariantSystem.ShaderFeature.MOTION_BLUR));

            VulkShadeConfig vcfg = VulkShadeConfig.getInstance();
            if (bloom != null) bloom.setIntensity(vcfg.getBloomIntensity());
            float mbs = vcfg.getMotionBlurStrength();
            if (motionBlur != null && Math.abs(mbs - lastMotionBlurStrength) > 0.001f) {
                motionBlur.setBlendFactor(mbs);
                lastMotionBlurStrength = mbs;
            }
        } catch (Exception e) {
            // ignore if ShaderVariantSystem not available
        }
    }

    private void transitionForRead(VkCommandBuffer cmd, VulkanImage img) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            img.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    private void recoverLayouts(VkCommandBuffer cmd, VulkanImage sceneColor) {
        if (sceneColor == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            sceneColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (tempBuffer != null) {
                tempBuffer.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_GENERAL);
            }
        }
    }

    private void effectBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
            barrier.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, barrier, null, null);
        }
    }

    private void transitionForWrite(VkCommandBuffer cmd, VulkanImage img) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            img.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_GENERAL);
        }
    }

    private void clearImageToBlack(VkCommandBuffer cmd, VulkanImage img) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue clearColor = VkClearColorValue.calloc(stack);
            clearColor.float32(0, 0.0f);
            clearColor.float32(1, 0.0f);
            clearColor.float32(2, 0.0f);
            clearColor.float32(3, 0.0f);

            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
            range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            range.baseMipLevel(0);
            range.levelCount(1);
            range.baseArrayLayer(0);
            range.layerCount(1);

            vkCmdClearColorImage(cmd, img.getId(), VK_IMAGE_LAYOUT_GENERAL, clearColor, range);

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            barrier.oldLayout(VK_IMAGE_LAYOUT_GENERAL);
            barrier.newLayout(VK_IMAGE_LAYOUT_GENERAL);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(img.getId());
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(1);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, null, null, barrier);
        }
    }

    private void blitBack(VkCommandBuffer cmd, VulkanImage src, VulkanImage dst) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            src.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dst.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            int w = Math.min(src.width, dst.width);
            int h = Math.min(src.height, dst.height);

            if (src.format == dst.format) {
                VkImageCopy.Buffer copyBuf = VkImageCopy.calloc(1, stack);
                copyBuf.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                copyBuf.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                copyBuf.extent().width(w);
                copyBuf.extent().height(h);
                copyBuf.extent().depth(1);
                vkCmdCopyImage(cmd, src.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copyBuf);
            } else {
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                blit.srcOffsets(1, VkOffset3D.calloc(stack).set(w, h, 1));
                blit.srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                blit.dstOffsets(1, VkOffset3D.calloc(stack).set(w, h, 1));
                blit.dstSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                vkCmdBlitImage(cmd, src.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST);
            }

            dst.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            src.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_GENERAL);
        }
    }
}
