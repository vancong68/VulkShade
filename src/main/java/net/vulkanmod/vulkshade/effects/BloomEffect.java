package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.shader.ComputePipeline;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

public class BloomEffect {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-Bloom");

    private boolean enabled = true;
    private float intensity = 0.7f;
    private float threshold = 1.0f;
    private int mipLevels = 5;

    private VulkanImage[] mipTextures;
    private int width;
    private int height;

    private ComputePipeline extractBrightPipeline;
    private ComputePipeline blurHPipeline;
    private ComputePipeline blurVPipeline;
    private ComputePipeline compositePipeline;

    public BloomEffect() {
    }

    public void initialize(int width, int height) {
        this.width = width;
        this.height = height;
        createMipChain();
        createPipelines();
    }

    public void render(VkCommandBuffer cmdBuffer, VulkanImage sceneColor,
                       VulkanImage outputImage) {
        if (!enabled) return;
        extractBrightPipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        extractBrightPipeline.bindImageDescriptor(1, outputImage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        int mipW = width / 2;
        int mipH = height / 2;
        extractBrightPipeline.dispatchWithDescriptors(cmdBuffer,
            (mipW + 15) / 16, (mipH + 15) / 16, 1);
    }

    public void resize(int newWidth, int newHeight) {
        cleanupMipChain();
        initialize(newWidth, newHeight);
    }

    public void cleanup() {
        cleanupMipChain();
        if (extractBrightPipeline != null) extractBrightPipeline.destroy();
        if (blurHPipeline != null) blurHPipeline.destroy();
        if (blurVPipeline != null) blurVPipeline.destroy();
        if (compositePipeline != null) compositePipeline.destroy();
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setIntensity(float intensity) { this.intensity = intensity; }
    public void setThreshold(float threshold) { this.threshold = threshold; }

    private void createMipChain() {
        mipTextures = new VulkanImage[mipLevels];
        int mipW = Math.max(1, width / 2);
        int mipH = Math.max(1, height / 2);
        for (int i = 0; i < mipLevels; i++) {
            mipTextures[i] = null;
            mipW = Math.max(1, mipW / 2);
            mipH = Math.max(1, mipH / 2);
        }
    }

    private void createPipelines() {
        extractBrightPipeline = new ComputePipeline("bloom_extract");
        extractBrightPipeline.compileFromSource(generateExtractSource());
        extractBrightPipeline.create();
        extractBrightPipeline.allocateDescriptorSet();

        blurHPipeline = new ComputePipeline("bloom_blur_h");
        blurHPipeline.compileFromSource(generateBlurHSource());
        blurHPipeline.create();

        blurVPipeline = new ComputePipeline("bloom_blur_v");
        blurVPipeline.compileFromSource(generateBlurVSource());
        blurVPipeline.create();
    }

    private String generateExtractSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0, rgba16f) uniform readonly image2D srcImage;
            layout(binding = 1, rgba16f) uniform writeonly image2D dstImage;
            layout(binding = 2) uniform BloomData {
                float intensity;
                float threshold;
            };
            vec3 luminanceWeight = vec3(0.2126, 0.7152, 0.0722);
            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(srcImage);
                if (coord.x >= size.x || coord.y >= size.y) return;
                vec3 color = imageLoad(srcImage, coord).rgb;
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
            layout(binding = 0, rgba16f) uniform readonly image2D srcImage;
            layout(binding = 1, rgba16f) uniform writeonly image2D dstImage;
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
            layout(binding = 0, rgba16f) uniform readonly image2D srcImage;
            layout(binding = 1, rgba16f) uniform writeonly image2D dstImage;
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

    private void cleanupMipChain() {
        if (mipTextures != null) {
            for (VulkanImage img : mipTextures) {
                if (img != null) img.free();
            }
            mipTextures = null;
        }
    }
}
