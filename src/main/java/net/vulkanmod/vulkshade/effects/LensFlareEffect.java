package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.shader.ComputePipeline;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

public class LensFlareEffect {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-LensFlare");

    private boolean enabled = true;
    private float intensity = 0.3f;
    private int ghostCount = 4;

    private ComputePipeline lensFlarePipeline;
    private int width;
    private int height;

    public LensFlareEffect() {
    }

    public void initialize(int width, int height) {
        this.width = width;
        this.height = height;
        createPipelines();
    }

    public void render(VkCommandBuffer cmdBuffer, VulkanImage sceneColor, VulkanImage outputImage) {
        if (!enabled) return;
        if (lensFlarePipeline == null || !lensFlarePipeline.isValid()) return;
        if (sceneColor == null || outputImage == null) return;

        lensFlarePipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        lensFlarePipeline.bindImageDescriptor(1, outputImage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);

        int groupX = (width + 15) / 16;
        int groupY = (height + 15) / 16;
        lensFlarePipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);
    }

    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
    }

    public void cleanup() {
        if (lensFlarePipeline != null) lensFlarePipeline.destroy();
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setIntensity(float intensity) { this.intensity = intensity; }
    public float getIntensity() { return intensity; }
    public void setGhostCount(int count) { this.ghostCount = Math.max(2, Math.min(8, count)); }
    public int getGhostCount() { return ghostCount; }

    private void createPipelines() {
        lensFlarePipeline = new ComputePipeline("lens_flare");
        lensFlarePipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        lensFlarePipeline.compileFromSource(generateLensFlareSource());
        lensFlarePipeline.create();
        if (lensFlarePipeline.isValid()) {
            lensFlarePipeline.allocateDescriptorSet();
            if (!lensFlarePipeline.isValid()) {
                LOGGER.warn("Lens flare pipeline allocation failed, disabling effect");
                this.enabled = false;
            }
        } else {
            LOGGER.warn("Lens flare pipeline compilation failed, disabling effect");
            this.enabled = false;
        }
    }

    private String generateLensFlareSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0) uniform sampler2D sceneColor;
            layout(binding = 1, rgba16f) uniform writeonly image2D outputImage;
            const float flareIntensity = %f;
            const int ghostCount = %d;
            const float ghostDispersal = 0.4;
            const float haloWidth = 0.5;
            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(outputImage);
                if (coord.x >= size.x || coord.y >= size.y) return;
                vec2 texelSize = 1.0 / vec2(size);
                vec2 uv = (vec2(coord) + 0.5) * texelSize;
                vec3 colorSample = texture(sceneColor, uv).rgb;
                float luminance = max(dot(colorSample, vec3(0.2126, 0.7152, 0.0722)) - 0.8, 0.0);
                vec2 flareCenter = vec2(0.5, 0.5);
                vec2 dir = uv - flareCenter;
                vec3 flare = vec3(0.0);
                for (int i = 0; i < ghostCount; i++) {
                    float t = float(i + 1) * ghostDispersal;
                    vec2 ghostUv = flareCenter - dir * t;
                    vec3 ghostColor = texture(sceneColor, ghostUv).rgb;
                    float ghostLum = max(dot(ghostColor, vec3(0.2126, 0.7152, 0.0722)) - 0.3, 0.0);
                    float fade = 1.0 - float(i) / float(ghostCount);
                    flare += ghostColor * ghostLum * fade * 0.5;
                }
                float haloDist = length(dir) / 0.5;
                if (haloDist < 1.5) {
                    float haloFade = 1.0 - haloDist * 0.4;
                    vec3 haloColor = texture(sceneColor, flareCenter + dir * haloWidth).rgb;
                    float haloIntensity = luminance * haloFade * 0.25;
                    flare += haloColor * haloIntensity;
                }
                vec3 result = colorSample + flare * flareIntensity;
                imageStore(outputImage, coord, vec4(result, 1.0));
            }
            """.formatted(intensity, ghostCount);
    }
}
