package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.shader.ComputePipeline;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.lwjgl.vulkan.VK10.*;

public class PBRDeferredLighting {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-PBR");

    private boolean enabled = true;
    private PBRQuality quality = PBRQuality.MEDIUM;

    private int gbufferWidth;
    private int gbufferHeight;
    private VulkanImage positionTexture;
    private VulkanImage normalTexture;
    private VulkanImage albedoTexture;
    private VulkanImage pbrTexture;
    private VulkanImage depthTexture;

    private ComputePipeline deferredLightingPipeline;

    public enum PBRQuality {
        LOW,
        MEDIUM,
        HIGH
    }

    public PBRDeferredLighting() {
    }

    public void initialize(int width, int height) {
        createGBuffer(width, height);
        createPipelines();
    }

    public void render(VkCommandBuffer cmdBuffer) {
        if (!enabled || positionTexture == null) return;

        int groupX = (gbufferWidth + 15) / 16;
        int groupY = (gbufferHeight + 15) / 16;
        deferredLightingPipeline.dispatch(cmdBuffer, groupX, groupY, 1);
    }

    public void resize(int newWidth, int newHeight) {
        cleanupGBuffer();
        initialize(newWidth, newHeight);
    }

    public void cleanup() {
        cleanupGBuffer();
        if (deferredLightingPipeline != null) {
            deferredLightingPipeline.destroy();
            deferredLightingPipeline = null;
        }
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
        this.gbufferWidth = width;
        this.gbufferHeight = height;
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
    }

    private void createPipelines() {
        deferredLightingPipeline = new ComputePipeline("pbr_deferred_lighting");
        deferredLightingPipeline.compileFromSource(generatePBRSource());
        deferredLightingPipeline.create();
    }

    private String generatePBRSource() {
        return """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

            layout(binding = 0, rgba16f) uniform readonly image2D gPosition;
            layout(binding = 1, rgba16f) uniform readonly image2D gNormal;
            layout(binding = 2, rgba8) uniform readonly image2D gAlbedo;
            layout(binding = 3, rgba8) uniform readonly image2D gPBR;
            layout(binding = 4, rgba16f) uniform writeonly image2D outputImage;

            layout(binding = 5) uniform PBRData {
                float metallicFactor;
                float roughnessFactor;
                float aoStrength;
                vec3 sunDirection;
                vec3 sunColor;
                vec3 ambientColor;
                int pbrQuality;
            };

            const float PI = 3.14159265359;

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

            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(gPosition);
                if (coord.x >= size.x || coord.y >= size.y) return;

                vec3 worldPos = imageLoad(gPosition, coord).xyz;
                vec3 N = normalize(imageLoad(gNormal, coord).xyz * 2.0 - 1.0);
                vec4 albedoAlpha = imageLoad(gAlbedo, coord);
                vec3 albedo = albedoAlpha.rgb;
                float alpha = albedoAlpha.a;
                vec3 pbrData = imageLoad(gPBR, coord).rgb;
                float metallic = pbrData.r * metallicFactor;
                float roughness = max(pbrData.g * roughnessFactor, 0.001);
                float ao = pbrData.b * aoStrength;

                vec3 V = normalize(-worldPos);
                vec3 F0 = mix(vec3(0.04), albedo, metallic);

                vec3 Lo = vec3(0.0);

                vec3 L = normalize(sunDirection);
                vec3 H = normalize(V + L);
                float distance = 1.0;
                float attenuation = 1.0 / (distance * distance);
                vec3 radiance = sunColor * attenuation;

                float NDF = distributionGGX(N, H, roughness);
                float G = geometrySmith(N, V, L, roughness);
                vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

                vec3 kS = F;
                vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);
                float NdotL = max(dot(N, L), 0.0);
                vec3 specular = NDF * G * F / max(4.0 * max(dot(N, V), 0.0) * NdotL, 0.001);
                Lo += (kD * albedo / PI + specular) * radiance * NdotL;

                vec3 ambient = ambientColor * albedo * ao;
                vec3 finalColor = ambient + Lo;
                imageStore(outputImage, coord, vec4(finalColor, alpha));
            }
            """;
    }

    private void cleanupGBuffer() {
        if (positionTexture != null) { positionTexture.free(); positionTexture = null; }
        if (normalTexture != null) { normalTexture.free(); normalTexture = null; }
        if (albedoTexture != null) { albedoTexture.free(); albedoTexture = null; }
        if (pbrTexture != null) { pbrTexture.free(); pbrTexture = null; }
        if (depthTexture != null) { depthTexture.free(); depthTexture = null; }
    }
}
