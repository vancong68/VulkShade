package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.shader.ComputePipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;

public class BloomEffect {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-Bloom");

    private boolean enabled = false;
    private float intensity = 0.5f;
    private float threshold = 0.5f;

    private VulkanImage blurScratch;
    private int width;
    private int height;

    private ComputePipeline extractBrightPipeline;
    private ComputePipeline blurHPipeline;
    private ComputePipeline blurVPipeline;
    private ComputePipeline compositePipeline;

    private Buffer bloomUBO;
    private static final int UBO_SIZE = 16;

    public BloomEffect() {
    }

    public void initialize(int width, int height) {
        this.width = width;
        this.height = height;
        createUBO();
        createBlurScratch();
        createPipelines();
    }

    public void render(VkCommandBuffer cmdBuffer, VulkanImage sceneColor,
                       VulkanImage outputImage) {
        if (!enabled) return;
        if (extractBrightPipeline == null || !extractBrightPipeline.isValid()) return;
        if (sceneColor == null || outputImage == null || blurScratch == null) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            blurScratch.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_GENERAL);
        }

        int groupX = (width + 15) / 16;
        int groupY = (height + 15) / 16;

        extractBrightPipeline.beginDefer();
        extractBrightPipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        extractBrightPipeline.bindImageDescriptor(1, outputImage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        bindUBO(extractBrightPipeline);
        extractBrightPipeline.endDefer();
        extractBrightPipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);
        computeBarrier(cmdBuffer);

        if (blurHPipeline == null || !blurHPipeline.isValid()) return;
        blurHPipeline.beginDefer();
        blurHPipeline.bindImageDescriptor(0, outputImage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        blurHPipeline.bindImageDescriptor(1, blurScratch, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        blurHPipeline.endDefer();
        blurHPipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);
        computeBarrier(cmdBuffer);

        if (blurVPipeline == null || !blurVPipeline.isValid()) return;
        blurVPipeline.beginDefer();
        blurVPipeline.bindImageDescriptor(0, blurScratch, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        blurVPipeline.bindImageDescriptor(1, outputImage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        blurVPipeline.endDefer();
        blurVPipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);
        computeBarrier(cmdBuffer);

        if (compositePipeline == null || !compositePipeline.isValid()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            outputImage.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        compositePipeline.beginDefer();
        compositePipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        compositePipeline.bindImageDescriptor(1, outputImage, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        compositePipeline.bindImageDescriptor(2, blurScratch, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        compositePipeline.endDefer();
        compositePipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);
    }

    private void computeBarrier(VkCommandBuffer cmdBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
            barrier.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmdBuffer,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, barrier, null, null);
        }
    }

    public VulkanImage getOutputImage() {
        return blurScratch;
    }

    public void resize(int newWidth, int newHeight) {
        cleanupScratch();
        initialize(newWidth, newHeight);
    }

    public void cleanup() {
        cleanupScratch();
        if (extractBrightPipeline != null) extractBrightPipeline.destroy();
        if (blurHPipeline != null) blurHPipeline.destroy();
        if (blurVPipeline != null) blurVPipeline.destroy();
        if (compositePipeline != null) compositePipeline.destroy();
        if (bloomUBO != null) { bloomUBO.scheduleFree(); bloomUBO = null; }
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setIntensity(float intensity) { this.intensity = intensity; }
    public void setThreshold(float threshold) { this.threshold = threshold; }

    private void createUBO() {
        bloomUBO = new Buffer(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, MemoryTypes.HOST_MEM);
        bloomUBO.createBuffer(UBO_SIZE);
    }

    private void bindUBO(ComputePipeline pipeline) {
        if (bloomUBO == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.malloc(UBO_SIZE);
            data.putFloat(0, intensity);
            data.putFloat(4, threshold);
            bloomUBO.copyBuffer(data, UBO_SIZE);
        }
        pipeline.bindUBO(2, bloomUBO);
    }

    private void createBlurScratch() {
        blurScratch = VulkanImage.builder(width, height)
            .setName("Bloom Blur Scratch")
            .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
            .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
            .setLinearFiltering(true)
            .setClamp(true)
            .createVulkanImage();
    }

    private void createPipelines() {
        extractBrightPipeline = new ComputePipeline("bloom_extract");
        extractBrightPipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(2, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
        extractBrightPipeline.compileFromSource(generateExtractSource());
        extractBrightPipeline.create();
        if (extractBrightPipeline.isValid()) {
            extractBrightPipeline.allocateDescriptorSet();
            if (!extractBrightPipeline.isValid()) {
                LOGGER.warn("Extract pipeline allocation failed");
                this.enabled = false;
            }
        } else {
            LOGGER.warn("Extract pipeline compilation failed, disabling bloom");
            this.enabled = false;
        }

        blurHPipeline = new ComputePipeline("bloom_blur_h");
        blurHPipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        blurHPipeline.compileFromSource(generateBlurHSource());
        blurHPipeline.create();
        if (blurHPipeline.isValid()) {
            blurHPipeline.allocateDescriptorSet();
        }

        blurVPipeline = new ComputePipeline("bloom_blur_v");
        blurVPipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        blurVPipeline.compileFromSource(generateBlurVSource());
        blurVPipeline.create();
        if (blurVPipeline.isValid()) {
            blurVPipeline.allocateDescriptorSet();
        }

        compositePipeline = new ComputePipeline("bloom_composite");
        compositePipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        compositePipeline.compileFromSource(generateCompositeSource());
        compositePipeline.create();
        if (compositePipeline.isValid()) {
            compositePipeline.allocateDescriptorSet();
        }
    }

    private String generateExtractSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0) uniform sampler2D srcImage;
            layout(binding = 1, rgba8) uniform writeonly image2D dstImage;
            layout(binding = 2, std140) uniform BloomData {
                float intensity;
                float threshold;
            };
            vec3 luminanceWeight = vec3(0.2126, 0.7152, 0.0722);
            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = textureSize(srcImage, 0);
                if (coord.x >= size.x || coord.y >= size.y) return;
                vec3 color = texelFetch(srcImage, coord, 0).rgb;
                float luminance = dot(color, luminanceWeight);
                float amount = max(luminance - threshold, 0.0) / max(1.0 - threshold, 0.001);
                vec3 bright = color * amount * intensity;
                imageStore(dstImage, coord, vec4(bright, 1.0));
            }
            """;
    }

    private String generateBlurHSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0, rgba8) uniform readonly image2D srcImage;
            layout(binding = 1, rgba8) uniform writeonly image2D dstImage;
            const float weights[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(srcImage);
                if (coord.x >= size.x || coord.y >= size.y) return;
                vec3 result = imageLoad(srcImage, coord).rgb * weights[0];
                for (int i = 1; i < 5; i++) {
                    ivec2 offset = ivec2(i, 0);
                    if (coord.x - offset.x >= 0) result += imageLoad(srcImage, coord - offset).rgb * weights[i];
                    if (coord.x + offset.x < size.x) result += imageLoad(srcImage, coord + offset).rgb * weights[i];
                }
                imageStore(dstImage, coord, vec4(result, 1.0));
            }
            """;
    }

    private String generateBlurVSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0, rgba8) uniform readonly image2D srcImage;
            layout(binding = 1, rgba8) uniform writeonly image2D dstImage;
            const float weights[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(srcImage);
                if (coord.x >= size.x || coord.y >= size.y) return;
                vec3 result = imageLoad(srcImage, coord).rgb * weights[0];
                for (int i = 1; i < 5; i++) {
                    ivec2 offset = ivec2(0, i);
                    if (coord.y - offset.y >= 0) result += imageLoad(srcImage, coord - offset).rgb * weights[i];
                    if (coord.y + offset.y < size.y) result += imageLoad(srcImage, coord + offset).rgb * weights[i];
                }
                imageStore(dstImage, coord, vec4(result, 1.0));
            }
            """;
    }

    private String generateCompositeSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0) uniform sampler2D originalScene;
            layout(binding = 1) uniform sampler2D blurredBright;
            layout(binding = 2, rgba8) uniform writeonly image2D outputImage;
            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(outputImage);
                if (coord.x >= size.x || coord.y >= size.y) return;
                vec3 original = texelFetch(originalScene, coord, 0).rgb;
                vec3 bright = texelFetch(blurredBright, coord, 0).rgb;
                vec3 result = original + bright;
                imageStore(outputImage, coord, vec4(result, 1.0));
            }
            """;
    }

    private void cleanupScratch() {
        if (blurScratch != null) { blurScratch.free(); blurScratch = null; }
    }
}
