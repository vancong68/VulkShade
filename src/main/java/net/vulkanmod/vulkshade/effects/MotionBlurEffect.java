package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.shader.ComputePipeline;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

public class MotionBlurEffect {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-MotionBlur");

    private boolean enabled = true;
    private float intensity = 0.5f;
    private int sampleCount = 8;

    private ComputePipeline motionBlurPipeline;
    private int width;
    private int height;

    public MotionBlurEffect() {
    }

    public void initialize(int width, int height) {
        this.width = width;
        this.height = height;
        createPipelines();
    }

    public void render(VkCommandBuffer cmdBuffer, VulkanImage sceneColor, VulkanImage outputImage) {
        if (!enabled) return;
        if (motionBlurPipeline == null || !motionBlurPipeline.isValid()) return;
        if (sceneColor == null || outputImage == null) return;

        motionBlurPipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        motionBlurPipeline.bindImageDescriptor(1, outputImage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);

        int groupX = (width + 15) / 16;
        int groupY = (height + 15) / 16;
        motionBlurPipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);
    }

    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
    }

    public void cleanup() {
        if (motionBlurPipeline != null) motionBlurPipeline.destroy();
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setIntensity(float intensity) { this.intensity = intensity; }
    public float getIntensity() { return intensity; }
    public void setSampleCount(int count) { this.sampleCount = Math.max(4, Math.min(32, count)); }
    public int getSampleCount() { return sampleCount; }

    private void createPipelines() {
        motionBlurPipeline = new ComputePipeline("motion_blur");
        motionBlurPipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        motionBlurPipeline.compileFromSource(generateMotionBlurSource());
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

    private String generateMotionBlurSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0) uniform sampler2D sceneColor;
            layout(binding = 1, rgba16f) uniform writeonly image2D outputImage;
            const int samples = %d;
            const float blurIntensity = %f;
            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(outputImage);
                if (coord.x >= size.x || coord.y >= size.y) return;
                vec2 texelSize = 1.0 / vec2(size);
                vec2 uv = (vec2(coord) + 0.5) * texelSize;
                vec3 color = texture(sceneColor, uv).rgb;
                vec2 blurDir = vec2(1.0, 0.0) * blurIntensity * 0.01;
                vec3 blurColor = color;
                for (int i = 1; i <= samples; i++) {
                    float t = float(i) / float(samples);
                    vec2 offset = blurDir * t;
                    blurColor += texture(sceneColor, uv + offset).rgb;
                    blurColor += texture(sceneColor, uv - offset).rgb;
                }
                blurColor /= float(samples * 2 + 1);
                imageStore(outputImage, coord, vec4(blurColor, 1.0));
            }
            """.formatted(sampleCount, intensity);
    }
}
