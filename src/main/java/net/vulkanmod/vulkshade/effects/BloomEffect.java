package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.shader.ComputePipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class BloomEffect {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-Bloom");

    private boolean enabled = false;
    private float intensity = 0.3f;
    private float threshold = 0.5f;

    private VulkanImage blurScratch;
    private VulkanImage bloomLevel1;
    private VulkanImage bloomLevel2;
    private VulkanImage bloomLevel3;
    private int width;
    private int height;

    private ComputePipeline extractBrightPipeline;
    private ComputePipeline kawaseDownPipeline;
    private ComputePipeline kawaseUpPipeline;
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
        createBloomLevels();
        createPipelines();
    }

    public void render(VkCommandBuffer cmdBuffer, VulkanImage sceneColor,
                       VulkanImage outputImage) {
        if (!enabled) return;
        if (extractBrightPipeline == null || !extractBrightPipeline.isValid()) return;
        if (sceneColor == null || outputImage == null || blurScratch == null) return;

        int l1w = Math.max(width / 2, 1);
        int l1h = Math.max(height / 2, 1);
        int l2w = Math.max(width / 4, 1);
        int l2h = Math.max(height / 4, 1);
        int l3w = Math.max(width / 8, 1);
        int l3h = Math.max(height / 8, 1);

        int gx0 = (width + 15) / 16;
        int gy0 = (height + 15) / 16;
        int gx1 = (l1w + 15) / 16;
        int gy1 = (l1h + 15) / 16;
        int gx2 = (l2w + 15) / 16;
        int gy2 = (l2h + 15) / 16;
        int gx3 = (l3w + 15) / 16;
        int gy3 = (l3h + 15) / 16;

        // 1. Extract bright pixels
        extractBrightPipeline.beginDefer();
        extractBrightPipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        extractBrightPipeline.bindImageDescriptor(1, outputImage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        bindUBO(extractBrightPipeline);
        extractBrightPipeline.endDefer();
        extractBrightPipeline.dispatchWithDescriptors(cmdBuffer, gx0, gy0, 1);
        computeBarrier(cmdBuffer);

        // 2. Kawase downsample chain: level0 -> level1 -> level2 -> level3
        if (!kawaseDownPipeline.isValid()) return;
        kawaseDownPipeline.beginDefer();
        kawaseDownPipeline.bindImageDescriptor(0, outputImage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseDownPipeline.bindImageDescriptor(1, bloomLevel1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseDownPipeline.endDefer();
        kawaseDownPipeline.dispatchWithDescriptors(cmdBuffer, gx1, gy1, 1);
        computeBarrier(cmdBuffer);

        kawaseDownPipeline.beginDefer();
        kawaseDownPipeline.bindImageDescriptor(0, bloomLevel1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseDownPipeline.bindImageDescriptor(1, bloomLevel2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseDownPipeline.endDefer();
        kawaseDownPipeline.dispatchWithDescriptors(cmdBuffer, gx2, gy2, 1);
        computeBarrier(cmdBuffer);

        kawaseDownPipeline.beginDefer();
        kawaseDownPipeline.bindImageDescriptor(0, bloomLevel2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseDownPipeline.bindImageDescriptor(1, bloomLevel3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseDownPipeline.endDefer();
        kawaseDownPipeline.dispatchWithDescriptors(cmdBuffer, gx3, gy3, 1);
        computeBarrier(cmdBuffer);

        // 3. Kawase upsample chain: accumulate back up
        if (!kawaseUpPipeline.isValid()) return;
        kawaseUpPipeline.beginDefer();
        kawaseUpPipeline.bindImageDescriptor(0, bloomLevel3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseUpPipeline.bindImageDescriptor(1, bloomLevel2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseUpPipeline.endDefer();
        kawaseUpPipeline.dispatchWithDescriptors(cmdBuffer, gx2, gy2, 1);
        computeBarrier(cmdBuffer);

        kawaseUpPipeline.beginDefer();
        kawaseUpPipeline.bindImageDescriptor(0, bloomLevel2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseUpPipeline.bindImageDescriptor(1, bloomLevel1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseUpPipeline.endDefer();
        kawaseUpPipeline.dispatchWithDescriptors(cmdBuffer, gx1, gy1, 1);
        computeBarrier(cmdBuffer);

        kawaseUpPipeline.beginDefer();
        kawaseUpPipeline.bindImageDescriptor(0, bloomLevel1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseUpPipeline.bindImageDescriptor(1, outputImage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseUpPipeline.endDefer();
        kawaseUpPipeline.dispatchWithDescriptors(cmdBuffer, gx0, gy0, 1);
        computeBarrier(cmdBuffer);

        // 4. Composite: original scene + accumulated bloom
        if (!compositePipeline.isValid()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            outputImage.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        compositePipeline.beginDefer();
        compositePipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        compositePipeline.bindImageDescriptor(1, outputImage, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        compositePipeline.bindImageDescriptor(2, blurScratch, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        compositePipeline.endDefer();
        compositePipeline.dispatchWithDescriptors(cmdBuffer, gx0, gy0, 1);
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
        if (kawaseDownPipeline != null) kawaseDownPipeline.destroy();
        if (kawaseUpPipeline != null) kawaseUpPipeline.destroy();
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

    private void createBloomLevels() {
        int l1w = Math.max(width / 2, 1);
        int l1h = Math.max(height / 2, 1);
        int l2w = Math.max(width / 4, 1);
        int l2h = Math.max(height / 4, 1);
        int l3w = Math.max(width / 8, 1);
        int l3h = Math.max(height / 8, 1);

        bloomLevel1 = createLevelImage(l1w, l1h, "Bloom Level 1");
        bloomLevel2 = createLevelImage(l2w, l2h, "Bloom Level 2");
        bloomLevel3 = createLevelImage(l3w, l3h, "Bloom Level 3");
    }

    private VulkanImage createLevelImage(int w, int h, String name) {
        return VulkanImage.builder(w, h)
            .setName(name)
            .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
            .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
            .setLinearFiltering(true)
            .setClamp(true)
            .createVulkanImage();
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

        kawaseDownPipeline = new ComputePipeline("bloom_kawase_down");
        kawaseDownPipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseDownPipeline.compileFromSource(generateKawaseDownSource());
        kawaseDownPipeline.create();
        if (kawaseDownPipeline.isValid()) {
            kawaseDownPipeline.allocateDescriptorSet();
        }

        kawaseUpPipeline = new ComputePipeline("bloom_kawase_up");
        kawaseUpPipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        kawaseUpPipeline.compileFromSource(generateKawaseUpSource());
        kawaseUpPipeline.create();
        if (kawaseUpPipeline.isValid()) {
            kawaseUpPipeline.allocateDescriptorSet();
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
                float above = luminance - threshold;
                float linearAmount = max(above, 0.0) / max(1.0 - threshold, 0.001);
                float knee = max(threshold * 0.5, 0.001);
                float t = clamp((luminance - threshold + knee) / (2.0 * knee), 0.0, 1.0);
                float softAmount = (t * t * (3.0 - 2.0 * t)) * knee / max(1.0 - threshold, 0.001);
                float amount = clamp(max(linearAmount, softAmount), 0.0, 1.0);
                vec3 bright = color * amount * intensity;
                imageStore(dstImage, coord, vec4(bright, 1.0));
            }
            """;
    }

    private String generateKawaseDownSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0, rgba8) uniform readonly image2D srcImage;
            layout(binding = 1, rgba8) uniform writeonly image2D dstImage;
            void main() {
                ivec2 dstC = ivec2(gl_GlobalInvocationID.xy);
                ivec2 dstSz = imageSize(dstImage);
                ivec2 srcSz = imageSize(srcImage);
                if (dstC.x >= dstSz.x || dstC.y >= dstSz.y) return;
                ivec2 base = dstC * 2;
                ivec2 maxC = srcSz - 1;
                vec4 c = imageLoad(srcImage, min(base + ivec2(0, 0), maxC));
                c += imageLoad(srcImage, min(base + ivec2(1, 0), maxC));
                c += imageLoad(srcImage, min(base + ivec2(0, 1), maxC));
                c += imageLoad(srcImage, min(base + ivec2(1, 1), maxC));
                c *= 0.25;
                imageStore(dstImage, dstC, c);
            }
            """;
    }

    private String generateKawaseUpSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0, rgba8) uniform readonly image2D srcImage;
            layout(binding = 1, rgba8) uniform image2D dstImage;
            void main() {
                ivec2 dstC = ivec2(gl_GlobalInvocationID.xy);
                ivec2 dstSz = imageSize(dstImage);
                ivec2 srcSz = imageSize(srcImage);
                if (dstC.x >= dstSz.x || dstC.y >= dstSz.y) return;
                vec2 uv = (vec2(dstC) + 0.5) / vec2(dstSz);
                vec2 srcCoord = uv * vec2(srcSz) - 0.5;
                ivec2 i = ivec2(floor(srcCoord));
                vec2 f = srcCoord - vec2(i);
                ivec2 maxC = srcSz - 1;
                vec4 c00 = imageLoad(srcImage, clamp(i + ivec2(0, 0), ivec2(0), maxC));
                vec4 c10 = imageLoad(srcImage, clamp(i + ivec2(1, 0), ivec2(0), maxC));
                vec4 c01 = imageLoad(srcImage, clamp(i + ivec2(0, 1), ivec2(0), maxC));
                vec4 c11 = imageLoad(srcImage, clamp(i + ivec2(1, 1), ivec2(0), maxC));
                vec4 c = mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
                vec4 existing = imageLoad(dstImage, dstC);
                imageStore(dstImage, dstC, existing + c * 0.5);
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
                float maxChannel = max(max(result.r, result.g), result.b);
                if (maxChannel > 1.0) {
                    result = result / maxChannel;
                }
                imageStore(outputImage, coord, vec4(result, 1.0));
            }
            """;
    }

    private void cleanupScratch() {
        if (blurScratch != null) { blurScratch.free(); blurScratch = null; }
        if (bloomLevel1 != null) { bloomLevel1.free(); bloomLevel1 = null; }
        if (bloomLevel2 != null) { bloomLevel2.free(); bloomLevel2 = null; }
        if (bloomLevel3 != null) { bloomLevel3.free(); bloomLevel3 = null; }
    }
}
