package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.shader.ComputePipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.lwjgl.vulkan.VK10.*;

public class LensFlareEffect {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-LensFlare");

    private boolean enabled = false;
    private float intensity = 1.0f;
    private float threshold = 2.0f;

    private ComputePipeline genPipeline;
    private ComputePipeline compositePipeline;
    private VulkanImage lensFlareBuffer;
    private VulkanImage previousAccum;
    private int width;
    private int height;
    private boolean firstFrame = true;

    public LensFlareEffect() {
    }

    public void initialize(int width, int height) {
        this.width = width;
        this.height = height;
        createBuffers();
        createPipelines();
        firstFrame = true;
    }

    public void render(VkCommandBuffer cmdBuffer, VulkanImage sceneColor, VulkanImage outputImage) {
        render(cmdBuffer, sceneColor, null, outputImage);
    }

    public void render(VkCommandBuffer cmdBuffer, VulkanImage sceneColor, VulkanImage depthBuffer, VulkanImage outputImage) {
        if (!enabled) return;
        if (sceneColor == null || outputImage == null) return;
        if (genPipeline == null || !genPipeline.isValid()) return;
        if (compositePipeline == null || !compositePipeline.isValid()) return;
        if (lensFlareBuffer == null) return;

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
                previousAccum.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                firstFrame = false;
            }

            lensFlareBuffer.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_GENERAL);

            genPipeline.beginDefer();
            genPipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            if (depthBuffer != null) {
                genPipeline.bindImageDescriptor(1, depthBuffer, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            }
            genPipeline.bindImageDescriptor(2, lensFlareBuffer, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            genPipeline.endDefer();

            int groupX = (width + 15) / 16;
            int groupY = (height + 15) / 16;
            genPipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);

            lensFlareBuffer.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            previousAccum.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            compositePipeline.beginDefer();
            compositePipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            compositePipeline.bindImageDescriptor(1, lensFlareBuffer, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            compositePipeline.bindImageDescriptor(2, previousAccum, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            compositePipeline.bindImageDescriptor(3, outputImage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            compositePipeline.endDefer();

            compositePipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);

            lensFlareBuffer.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            previousAccum.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            VkImageCopy.Buffer accumCopy = VkImageCopy.calloc(1, stack);
            accumCopy.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            accumCopy.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            accumCopy.extent().width(Math.min(lensFlareBuffer.width, previousAccum.width));
            accumCopy.extent().height(Math.min(lensFlareBuffer.height, previousAccum.height));
            accumCopy.extent().depth(1);
            vkCmdCopyImage(cmdBuffer, lensFlareBuffer.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                previousAccum.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, accumCopy);

            lensFlareBuffer.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_GENERAL);
        }
    }

    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
        cleanupBuffers();
        createBuffers();
        firstFrame = true;
    }

    public void cleanup() {
        if (genPipeline != null) genPipeline.destroy();
        if (compositePipeline != null) compositePipeline.destroy();
        cleanupBuffers();
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setIntensity(float intensity) {
        this.intensity = Math.max(0.0f, intensity);
        if (genPipeline != null) { genPipeline.destroy(); createPipelines(); }
    }
    public float getIntensity() { return intensity; }
    public void setThreshold(float threshold) {
        this.threshold = Math.max(0.5f, threshold);
        if (genPipeline != null) { genPipeline.destroy(); createPipelines(); }
    }
    public float getThreshold() { return threshold; }

    private void createBuffers() {
        lensFlareBuffer = VulkanImage.builder(width, height)
            .setName("LensFlare Buffer")
            .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
            .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
            .setLinearFiltering(true)
            .setClamp(true)
            .createVulkanImage();

        previousAccum = VulkanImage.builder(width, height)
            .setName("LensFlare Accum")
            .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
            .setUsage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
            .setLinearFiltering(true)
            .setClamp(true)
            .createVulkanImage();
    }

    private void cleanupBuffers() {
        if (lensFlareBuffer != null) lensFlareBuffer.free();
        if (previousAccum != null) previousAccum.free();
        lensFlareBuffer = null;
        previousAccum = null;
    }

    private void createPipelines() {
        genPipeline = new ComputePipeline("lens_flare_gen");
        genPipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        genPipeline.compileFromSource(generateGenSource());
        genPipeline.create();
        if (genPipeline.isValid()) {
            genPipeline.allocateDescriptorSet();
            if (!genPipeline.isValid()) {
                LOGGER.warn("Lens flare gen pipeline allocation failed, disabling effect");
                this.enabled = false;
            }
        } else {
            LOGGER.warn("Lens flare gen pipeline compilation failed, disabling effect");
            this.enabled = false;
        }

        compositePipeline = new ComputePipeline("lens_flare_composite");
        compositePipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        compositePipeline.compileFromSource(generateCompositeSource());
        compositePipeline.create();
        if (compositePipeline.isValid()) {
            compositePipeline.allocateDescriptorSet();
            if (!compositePipeline.isValid()) {
                LOGGER.warn("Lens flare composite pipeline allocation failed, disabling effect");
                this.enabled = false;
            }
        } else {
            LOGGER.warn("Lens flare composite pipeline compilation failed, disabling effect");
            this.enabled = false;
        }
    }

    private String generateGenSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0) uniform sampler2D sceneColor;
            layout(binding = 1) uniform sampler2D depthBuffer;
            layout(binding = 2, rgba8) uniform writeonly image2D flareOutput;

            const float BRIGHTNESS_THRESHOLD = __THRESHOLD__;
            const float FLARE_INTENSITY = __INTENSITY__;

            vec3 sampleBright(vec2 uv) {
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec3(0.0);
                return texture(sceneColor, uv).rgb;
            }

            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(flareOutput);
                if (coord.x >= size.x || coord.y >= size.y) return;

                vec2 texelSize = 1.0 / vec2(size);
                vec2 uv = (vec2(coord) + 0.5) * texelSize;
                vec2 center = vec2(0.5, 0.5);
                vec2 dir = uv - center;

                vec3 flare = vec3(0.0);

                const int GHOST_COUNT = 8;
                const float GHOST_OFFSETS[GHOST_COUNT] = float[](-0.25, 0.12, -0.45, 0.22, -0.65, 0.35, -0.90, 0.55);
                const float GHOST_SCALES[GHOST_COUNT] = float[](0.35, 0.25, 0.55, 0.18, 0.75, 0.12, 0.45, 0.08);

                for (int i = 0; i < GHOST_COUNT; i++) {
                    float off = GHOST_OFFSETS[i];
                    if (abs(off) < 0.01) continue;
                    vec2 lightUV = center + (center - uv) / off;
                    if (lightUV.x < 0.0 || lightUV.x > 1.0 || lightUV.y < 0.0 || lightUV.y > 1.0) continue;

                    vec3 lightColor = sampleBright(lightUV);
                    float lum = max(dot(lightColor, vec3(0.2126, 0.7152, 0.0722)) - BRIGHTNESS_THRESHOLD, 0.0);
                    if (lum <= 0.0) continue;

                    float scale = GHOST_SCALES[i];
                    vec2 chromaOff = vec2(scale * 0.008, 0.0);
                    float r = sampleBright(lightUV + chromaOff).r;
                    float g = lightColor.g;
                    float b = sampleBright(lightUV - chromaOff).b;

                    float fade = 1.0 - abs(off) * 0.5;
                    fade = max(fade, 0.0);
                    float weight = scale * fade * lum * FLARE_INTENSITY;

                    flare += vec3(r, g, b) * weight;
                }

                float dist = length(dir) * 2.0;
                if (dist < 1.8) {
                    vec2 haloUV = center + dir * 0.4;
                    vec3 haloColor = sampleBright(haloUV);
                    float haloLum = max(dot(haloColor, vec3(0.2126, 0.7152, 0.0722)) - BRIGHTNESS_THRESHOLD, 0.0);
                    if (haloLum > 0.0) {
                        float falloff = exp(-dist * dist * 1.5) * 0.5 * FLARE_INTENSITY;
                        flare += haloColor * pow(haloLum, 1.5) * falloff;
                    }
                }

                const int APERTURE_BLADES = 6;
                float spikeIntensity = 0.0;
                for (int j = 0; j < APERTURE_BLADES; j++) {
                    float angle = float(j) * 6.283185 / float(APERTURE_BLADES);
                    vec2 spikeDir = vec2(cos(angle), sin(angle));
                    float spikeDist = 0.01;
                    for (int k = 0; k < 6; k++) {
                        vec2 spikeUV = uv + spikeDir * spikeDist;
                        spikeDist += 0.005;
                        vec3 spikeColor = sampleBright(spikeUV);
                        float spikeLum = max(dot(spikeColor, vec3(0.2126, 0.7152, 0.0722)) - BRIGHTNESS_THRESHOLD, 0.0);
                        spikeIntensity += spikeLum * 0.15;
                    }
                }
                float spikeWeight = spikeIntensity * 0.06 * FLARE_INTENSITY;
                float spikeFade = max(1.0 - dist * 1.5, 0.0);
                flare += vec3(spikeWeight * spikeFade);

                imageStore(flareOutput, coord, vec4(flare, 1.0));
            }
            """.replace("__THRESHOLD__", String.format("%.2f", threshold))
              .replace("__INTENSITY__", String.format("%.2f", intensity));
    }

    private String generateCompositeSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0) uniform sampler2D sceneColor;
            layout(binding = 1) uniform sampler2D flareBuffer;
            layout(binding = 2) uniform sampler2D previousAccum;
            layout(binding = 3, rgba8) uniform writeonly image2D outputImage;

            const float FLARE_INTENSITY = __INTENSITY__;
            const float TEMPORAL_FACTOR = 0.15;

            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(outputImage);
                if (coord.x >= size.x || coord.y >= size.y) return;

                vec2 texelSize = 1.0 / vec2(size);
                vec2 uv = (vec2(coord) + 0.5) * texelSize;

                vec3 scene = texture(sceneColor, uv).rgb;
                vec3 newFlare = texture(flareBuffer, uv).rgb;
                vec3 oldFlare = texture(previousAccum, uv).rgb;

                vec3 stableFlare = mix(oldFlare, newFlare, TEMPORAL_FACTOR);

                vec3 streakFlare = vec3(0.0);
                float totalWeight = 0.0;
                int streakSamples = 8;
                float streakSigma = 3.5;
                float streakLength = 1.5;
                for (int i = -streakSamples; i <= streakSamples; i++) {
                    if (i == 0) continue;
                    vec2 offset = vec2(float(i) * texelSize.x * streakLength, 0.0);
                    float weight = exp(-float(i * i) / (2.0 * streakSigma * streakSigma));
                    vec3 s = texture(flareBuffer, uv + offset).rgb;
                    float sLum = dot(s, vec3(0.2126, 0.7152, 0.0722));
                    streakFlare += s * weight * sLum;
                    totalWeight += weight;
                }
                if (totalWeight > 0.0) streakFlare /= totalWeight;

                vec3 result = scene + (stableFlare + streakFlare * 0.6) * FLARE_INTENSITY;

                result = clamp(result, 0.0, 1.0);

                imageStore(outputImage, coord, vec4(result, 1.0));
            }
            """.replace("__INTENSITY__", String.format("%.2f", intensity));
    }
}
