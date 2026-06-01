package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.shader.ComputePipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.lwjgl.vulkan.VK10.*;

public class PBRDeferredLighting {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-PBR");

    private boolean enabled = false;
    private PBRQuality quality = PBRQuality.MEDIUM;
    private float metallicFactor = 0.5f;
    private float roughnessFactor = 0.6f;
    private float aoStrength = 1.0f;

    private int gbufferWidth;
    private int gbufferHeight;
    private VulkanImage positionTexture;
    private VulkanImage normalTexture;
    private VulkanImage albedoTexture;
    private VulkanImage pbrTexture;
    private VulkanImage depthTexture;
    private VulkanImage lightingOutput;

    private ComputePipeline gbufferFillPipeline;
    private ComputePipeline deferredLightingPipeline;
    private ComputePipeline compositePipeline;

    private boolean firstFrame = true;

    public enum PBRQuality { LOW, MEDIUM, HIGH }

    public PBRDeferredLighting() {
    }

    public void initialize(int width, int height) {
        this.gbufferWidth = width;
        this.gbufferHeight = height;
        createGBuffer(width, height);
        createLightingOutput(width, height);
        createPipelines();
        firstFrame = true;
    }

    public void render(VkCommandBuffer cmdBuffer, VulkanImage sceneColor, VulkanImage depthBuffer) {
        if (!enabled || !isReady()) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            sceneColor.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (depthBuffer != null) {
                depthBuffer.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            transitionGBufferForWrite(stack, cmdBuffer);

            gbufferFillPipeline.beginDefer();
            gbufferFillPipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            if (depthBuffer != null) {
                gbufferFillPipeline.bindImageDescriptor(1, depthBuffer, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            }
            gbufferFillPipeline.bindImageDescriptor(2, positionTexture, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            gbufferFillPipeline.bindImageDescriptor(3, normalTexture, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            gbufferFillPipeline.bindImageDescriptor(4, albedoTexture, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            gbufferFillPipeline.bindImageDescriptor(5, pbrTexture, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            gbufferFillPipeline.endDefer();

            int groupX = (gbufferWidth + 15) / 16;
            int groupY = (gbufferHeight + 15) / 16;
            gbufferFillPipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);

            barrierGBufferToRead(stack, cmdBuffer);
            lightingOutput.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_GENERAL);

            deferredLightingPipeline.beginDefer();
            deferredLightingPipeline.bindImageDescriptor(0, positionTexture, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            deferredLightingPipeline.bindImageDescriptor(1, normalTexture, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            deferredLightingPipeline.bindImageDescriptor(2, albedoTexture, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            deferredLightingPipeline.bindImageDescriptor(3, pbrTexture, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            deferredLightingPipeline.bindImageDescriptor(4, lightingOutput, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            deferredLightingPipeline.endDefer();

            deferredLightingPipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);

            lightingOutput.transitionImageLayout(stack, cmdBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            compositePipeline.beginDefer();
            compositePipeline.bindImageDescriptor(0, sceneColor, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            compositePipeline.bindImageDescriptor(1, lightingOutput, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            compositePipeline.bindImageDescriptor(2, sceneColor, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            compositePipeline.endDefer();

            compositePipeline.dispatchWithDescriptors(cmdBuffer, groupX, groupY, 1);
        }
    }

    public void resize(int newWidth, int newHeight) {
        cleanupGBuffer();
        if (lightingOutput != null) lightingOutput.free();
        initialize(newWidth, newHeight);
    }

    public void cleanup() {
        cleanupGBuffer();
        if (lightingOutput != null) { lightingOutput.free(); lightingOutput = null; }
        if (gbufferFillPipeline != null) { gbufferFillPipeline.destroy(); gbufferFillPipeline = null; }
        if (deferredLightingPipeline != null) { deferredLightingPipeline.destroy(); deferredLightingPipeline = null; }
        if (compositePipeline != null) { compositePipeline.destroy(); compositePipeline = null; }
    }

    public VulkanImage getPositionTexture() { return positionTexture; }
    public VulkanImage getNormalTexture() { return normalTexture; }
    public VulkanImage getAlbedoTexture() { return albedoTexture; }
    public VulkanImage getPbrTexture() { return pbrTexture; }
    public VulkanImage getDepthTexture() { return depthTexture; }
    public boolean isReady() { return positionTexture != null && normalTexture != null
        && albedoTexture != null && pbrTexture != null && depthTexture != null; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setQuality(PBRQuality quality) { this.quality = quality; }
    public PBRQuality getQuality() { return quality; }
    public void setMetallicFactor(float f) { this.metallicFactor = f; }
    public void setRoughnessFactor(float f) { this.roughnessFactor = f; }
    public void setAoStrength(float f) { this.aoStrength = f; }

    public UBO createPBRUBO(int binding) {
        AlignedStruct.Builder builder = new AlignedStruct.Builder();
        builder.addUniformInfo(Uniform.createUniformInfo("float", "metallicFactor", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("float", "roughnessFactor", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("float", "aoStrength", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("vec3", "sunDirection", 3));
        builder.addUniformInfo(Uniform.createUniformInfo("vec3", "sunColor", 3));
        builder.addUniformInfo(Uniform.createUniformInfo("vec3", "ambientColor", 3));
        builder.addUniformInfo(Uniform.createUniformInfo("int", "pbrQuality", 1));
        var ubo = builder.buildUBO(binding, -1);
        ubo.setUseGlobalBuffer(false);
        return ubo;
    }

    private void createGBuffer(int width, int height) {
        cleanupGBuffer();
        this.positionTexture = VulkanImage.builder(width, height)
            .setName("GBuffer Position")
            .setFormat(VK_FORMAT_R16G16B16A16_SFLOAT)
            .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
            .setLinearFiltering(false).setClamp(true)
            .createVulkanImage();
        this.normalTexture = VulkanImage.builder(width, height)
            .setName("GBuffer Normal")
            .setFormat(VK_FORMAT_R16G16B16A16_SFLOAT)
            .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
            .setLinearFiltering(false).setClamp(true)
            .createVulkanImage();
        this.albedoTexture = VulkanImage.builder(width, height)
            .setName("GBuffer Albedo")
            .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
            .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
            .setLinearFiltering(true).setClamp(true)
            .createVulkanImage();
        this.pbrTexture = VulkanImage.builder(width, height)
            .setName("GBuffer PBR")
            .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
            .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
            .setLinearFiltering(false).setClamp(true)
            .createVulkanImage();
        this.depthTexture = VulkanImage.builder(width, height)
            .setName("GBuffer Depth")
            .setFormat(VK_FORMAT_D32_SFLOAT)
            .setUsage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)
            .setLinearFiltering(false).setClamp(true)
            .createVulkanImage();
        this.gbufferWidth = width;
        this.gbufferHeight = height;
    }

    private void createLightingOutput(int width, int height) {
        if (lightingOutput != null) lightingOutput.free();
        this.lightingOutput = VulkanImage.builder(width, height)
            .setName("PBR Lighting Output")
            .setFormat(VK_FORMAT_R16G16B16A16_SFLOAT)
            .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
            .setLinearFiltering(true).setClamp(true)
            .createVulkanImage();
    }

    private void createPipelines() {
        gbufferFillPipeline = new ComputePipeline("pbr_gbuffer_fill");
        gbufferFillPipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        gbufferFillPipeline.compileFromSource(generateGbufferFillSource());
        gbufferFillPipeline.create();
        if (gbufferFillPipeline.isValid()) {
            gbufferFillPipeline.allocateDescriptorSet();
            if (!gbufferFillPipeline.isValid()) {
                LOGGER.warn("PBR GBuffer fill allocation failed, disabling");
                this.enabled = false; return;
            }
        } else {
            LOGGER.warn("PBR GBuffer fill compilation failed, disabling");
            this.enabled = false; return;
        }

        deferredLightingPipeline = new ComputePipeline("pbr_deferred_lighting");
        deferredLightingPipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .addDescriptorBinding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        deferredLightingPipeline.compileFromSource(generatePBRSource());
        deferredLightingPipeline.create();
        if (deferredLightingPipeline.isValid()) {
            deferredLightingPipeline.allocateDescriptorSet();
            if (!deferredLightingPipeline.isValid()) {
                LOGGER.warn("PBR lighting allocation failed, disabling");
                this.enabled = false; return;
            }
        } else {
            LOGGER.warn("PBR lighting compilation failed, disabling");
            this.enabled = false; return;
        }

        compositePipeline = new ComputePipeline("pbr_composite");
        compositePipeline
            .addDescriptorBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .addDescriptorBinding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        compositePipeline.compileFromSource(generateCompositeSource());
        compositePipeline.create();
        if (compositePipeline.isValid()) {
            compositePipeline.allocateDescriptorSet();
        } else {
            LOGGER.warn("PBR composite compilation failed, disabling");
            this.enabled = false;
        }
    }

    private String generateGbufferFillSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0) uniform sampler2D sceneColor;
            layout(binding = 1) uniform sampler2D depthBuffer;
            layout(binding = 2, rgba16f) uniform writeonly image2D gPosition;
            layout(binding = 3, rgba16f) uniform writeonly image2D gNormal;
            layout(binding = 4, rgba8) uniform writeonly image2D gAlbedo;
            layout(binding = 5, rgba8) uniform writeonly image2D gPBR;

            vec3 reconstructNormal(vec2 uv, vec2 texelSize) {
                float d = texture(depthBuffer, uv).r;
                float dL = texture(depthBuffer, uv + vec2(texelSize.x, 0.0)).r;
                float dR = texture(depthBuffer, uv - vec2(texelSize.x, 0.0)).r;
                float dD = texture(depthBuffer, uv + vec2(0.0, texelSize.y)).r;
                float dU = texture(depthBuffer, uv - vec2(0.0, texelSize.y)).r;
                float dx = dR - dL;
                float dy = dD - dU;
                vec3 n = normalize(vec3(-dx, -dy, 1.0 / max(abs(dx) + abs(dy), 0.001)));
                return n;
            }

            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(gPosition);
                if (coord.x >= size.x || coord.y >= size.y) return;
                vec2 texelSize = 1.0 / vec2(size);
                vec2 uv = (vec2(coord) + 0.5) * texelSize;
                vec3 albedo = texture(sceneColor, uv).rgb;
                vec3 N = reconstructNormal(uv, texelSize);
                float depth = texture(depthBuffer, uv).r;
                vec3 pos = vec3(uv * 2.0 - 1.0, depth);
                float metallic = 0.0;
                float roughness = 0.6;
                float ao = 1.0;
                imageStore(gPosition, coord, vec4(pos, 1.0));
                imageStore(gNormal, coord, vec4(N * 0.5 + 0.5, 1.0));
                imageStore(gAlbedo, coord, vec4(albedo, 1.0));
                imageStore(gPBR, coord, vec4(metallic, roughness, ao, 1.0));
            }
            """;
    }

    private String generatePBRSource() {
        boolean highQuality = quality == PBRQuality.HIGH;
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0, rgba16f) uniform readonly image2D gPosition;
            layout(binding = 1, rgba16f) uniform readonly image2D gNormal;
            layout(binding = 2, rgba8) uniform readonly image2D gAlbedo;
            layout(binding = 3, rgba8) uniform readonly image2D gPBR;
            layout(binding = 4, rgba16f) uniform writeonly image2D outputImage;

            const float PI = 3.14159265359;
            const float METALLIC_FACTOR = __METALLIC__;
            const float ROUGHNESS_FACTOR = __ROUGHNESS__;
            const float AO_STRENGTH = __AO__;

            float distributionGGX(vec3 N, vec3 H, float roughness) {
                float a = roughness * roughness;
                float a2 = a * a;
                float NdotH = max(dot(N, H), 0.0);
                float NdotH2 = NdotH * NdotH;
                float denom = NdotH2 * (a2 - 1.0) + 1.0;
                return a2 / (PI * denom * denom);
            }

            float geometrySchlickGGX(float NdotV, float roughness) {
                float r = roughness + 1.0;
                float k = (r * r) / 8.0;
                return NdotV / (NdotV * (1.0 - k) + k);
            }

            float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
                return geometrySchlickGGX(max(dot(N, V), 0.0), roughness) *
                       geometrySchlickGGX(max(dot(N, L), 0.0), roughness);
            }

            vec3 fresnelSchlick(float cosTheta, vec3 F0) {
                return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
            }

            vec3 fresnelSchlickRoughness(float cosTheta, vec3 F0, float roughness) {
                return F0 + (max(vec3(1.0 - roughness), F0) - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
            }

            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(gPosition);
                if (coord.x >= size.x || coord.y >= size.y) return;

                vec3 worldPos = imageLoad(gPosition, coord).xyz;
                vec3 N = normalize(imageLoad(gNormal, coord).xyz * 2.0 - 1.0);
                vec3 albedo = imageLoad(gAlbedo, coord).rgb;
                vec3 pbrData = imageLoad(gPBR, coord).rgb;
                float metallic = pbrData.r * METALLIC_FACTOR;
                float roughness = max(pbrData.g * ROUGHNESS_FACTOR, 0.001);
                float ao = pbrData.b * AO_STRENGTH;

                vec3 V = normalize(vec3(0.0, 0.0, 1.0));
                vec3 F0 = mix(vec3(0.04), albedo, metallic);

                vec3 Lo = vec3(0.0);

                vec3 L = normalize(vec3(0.5, 0.8, 0.6));
                vec3 H = normalize(V + L);
                vec3 radiance = vec3(2.0, 1.8, 1.6);

                float NDF = distributionGGX(N, H, roughness);
                float G = geometrySmith(N, V, L, roughness);
                vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

                vec3 kS = F;
                vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);
                float NdotL = max(dot(N, L), 0.0);
                vec3 specular = NDF * G * F / max(4.0 * max(dot(N, V), 0.0) * NdotL, 0.001);
                Lo += (kD * albedo / PI + specular) * radiance * NdotL;

                """ + (highQuality ? """
                float NdotV = max(dot(N, V), 0.0);
                vec3 multiScatter = (vec3(1.0) - kS) * (1.0 - geometrySmith(N, V, L, roughness));
                Lo += multiScatter * albedo * radiance * NdotL * 0.3;

                float specOcc = clamp(pow(NdotV + roughness, 2.0), 0.0, 1.0);
                """ : """
                float specOcc = 1.0;
                """) + """
                vec3 ambient = vec3(0.12, 0.14, 0.18) * albedo * max(ao, 0.3);
                vec3 finalColor = ambient + Lo;
                imageStore(outputImage, coord, vec4(finalColor, 1.0));
            }
            """.replace("__METALLIC__", String.format("%.2f", metallicFactor))
              .replace("__ROUGHNESS__", String.format("%.2f", roughnessFactor))
              .replace("__AO__", String.format("%.2f", aoStrength));
    }

    private String generateCompositeSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
            layout(binding = 0) uniform sampler2D sceneColor;
            layout(binding = 1) uniform sampler2D lightingBuffer;
            layout(binding = 2, rgba8) uniform writeonly image2D outputImage;

            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(outputImage);
                if (coord.x >= size.x || coord.y >= size.y) return;
                vec2 texelSize = 1.0 / vec2(size);
                vec2 uv = (vec2(coord) + 0.5) * texelSize;
                vec3 scene = texture(sceneColor, uv).rgb;
                vec3 pbr = texture(lightingBuffer, uv).rgb;
                vec3 result = scene + pbr * 0.6;
                result = clamp(result, 0.0, 1.0);
                imageStore(outputImage, coord, vec4(result, 1.0));
            }
            """;
    }

    private void transitionGBufferForWrite(MemoryStack stack, VkCommandBuffer cmd) {
        for (VulkanImage img : new VulkanImage[]{positionTexture, normalTexture, albedoTexture, pbrTexture}) {
            if (img != null) img.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_GENERAL);
        }
    }

    private void barrierGBufferToRead(MemoryStack stack, VkCommandBuffer cmd) {
        for (VulkanImage img : new VulkanImage[]{positionTexture, normalTexture, albedoTexture, pbrTexture}) {
            if (img != null) img.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    private void cleanupGBuffer() {
        if (positionTexture != null) { positionTexture.free(); positionTexture = null; }
        if (normalTexture != null) { normalTexture.free(); normalTexture = null; }
        if (albedoTexture != null) { albedoTexture.free(); albedoTexture = null; }
        if (pbrTexture != null) { pbrTexture.free(); pbrTexture = null; }
        if (depthTexture != null) { depthTexture.free(); depthTexture = null; }
    }
}
