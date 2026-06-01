package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.shader.ComputePipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.lwjgl.vulkan.VK10.*;

public class MotionBlurEffect {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-MotionBlur");

    private boolean enabled = false;
    private float blendFactor = 0.5f;

    private ComputePipeline motionBlurPipeline;
    private VulkanImage previousAccum;
    private int width;
    private int height;
    private boolean firstFrame = true;

    public MotionBlurEffect() {
    }

    public void initialize(int width, int height) {
        this.width = width;
        this.height = height;
        createPreviousAccum();
        createPipelines();
        firstFrame = true;
    }

    public void render(VkCommandBuffer cmdBuffer, VulkanImage sceneColor, VulkanImage outputImage) {
        if (!enabled) return;
        if (motionBlurPipeline == null || !motionBlurPipeline.isValid()) return;
        if (sceneColor == null || outputImage == null || previousAccum == null) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (firstFrame) {
                sceneColor.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                previousAccum.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

                VkImageCopy.Buffer firstCopy = VkImageCopy.calloc(1, stack);
                firstCopy.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                firstCopy.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                firstCopy.extent().width(Math.min(sceneColor.width, previousAccum.width));
                firstCopy.extent().height(Math.min(sceneColor.height, previousAccum.height));
                firstCopy.extent().depth(1);
                vkCmdCopyImage(cmdBuffer, sceneColor.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    previousAccum.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, firstCopy);

                sceneColor.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                firstFrame = false;
            }

            previousAccum.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            motionBlurPipeline.beginDefer();
            motionBlurPipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            motionBlurPipeline.bindImageDescriptor(1, previousAccum, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            motionBlurPipeline.bindImageDescriptor(2, outputImage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            motionBlurPipeline.endDefer();

            int groupX = (width + 15) / 16;
            int groupY = (height + 15) / 16;
            motionBlurPipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);

            outputImage.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            previousAccum.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            VkImageCopy.Buffer accumCopy = VkImageCopy.calloc(1, stack);
            accumCopy.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            accumCopy.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            accumCopy.extent().width(Math.min(outputImage.width, previousAccum.width));
            accumCopy.extent().height(Math.min(outputImage.height, previousAccum.height));
            accumCopy.extent().depth(1);
            vkCmdCopyImage(cmdBuffer, outputImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                previousAccum.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, accumCopy);

            outputImage.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_GENERAL);
        }
    }

    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
        if (previousAccum != null) previousAccum.free();
        previousAccum = null;
        createPreviousAccum();
        firstFrame = true;
    }

    public void cleanup() {
        if (motionBlurPipeline != null) motionBlurPipeline.destroy();
        if (previousAccum != null) previousAccum.free();
        previousAccum = null;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setBlendFactor(float factor) {
        this.blendFactor = Math.max(0.0f, Math.min(0.99f, factor));
        if (motionBlurPipeline != null) {
            motionBlurPipeline.destroy();
            createPipelines();
        }
    }
    public float getBlendFactor() { return blendFactor; }

    private void createPreviousAccum() {
        previousAccum = VulkanImage.builder(width, height)
            .setName("MotionBlur Accum")
            .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
            .setUsage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
            .setLinearFiltering(true)
            .setClamp(true)
            .createVulkanImage();
    }

    private void createPipelines() {
        motionBlurPipeline = new ComputePipeline("motion_blur");
        motionBlurPipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        motionBlurPipeline.compileFromSource(generateSource());
        motionBlurPipeline.create();
        if (motionBlurPipeline.isValid()) {
            motionBlurPipeline.allocateDescriptorSet();
            if (!motionBlurPipeline.isValid()) {
                LOGGER.warn("Motion blur pipeline allocation failed, disabling effect");
                this.enabled = false;
            }
        } else {
            LOGGER.warn("Motion blur pipeline compilation failed, disabling effect");
            this.enabled = false;
        }
    }

    private String generateSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0) uniform sampler2D sceneColor;
            layout(binding = 1) uniform sampler2D previousAccum;
            layout(binding = 2, rgba8) uniform writeonly image2D outputImage;
            const float blendFactor = __BLEND__;
            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(outputImage);
                if (coord.x >= size.x || coord.y >= size.y) return;
                vec2 texelSize = 1.0 / vec2(size);
                vec2 uv = (vec2(coord) + 0.5) * texelSize;
                vec3 curr = texture(sceneColor, uv).rgb;
                vec3 prev = texture(previousAccum, uv).rgb;
                vec3 result = mix(curr, prev, blendFactor);
                imageStore(outputImage, coord, vec4(result, 1.0));
            }
            """.replace("__BLEND__", String.format("%.3f", blendFactor));
    }
}
