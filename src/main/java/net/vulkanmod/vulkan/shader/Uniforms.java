package net.vulkanmod.vulkan.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.function.Supplier;

public class Uniforms {
    private static final Minecraft MINECRAFT = Minecraft.getInstance();

    public static Object2ReferenceOpenHashMap<String, Supplier<Integer>> vec1i_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static Object2ReferenceOpenHashMap<String, Supplier<Float>> vec1f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec2f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec3f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec4f_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> mat4f_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static void setupDefaultUniforms() {

        //Mat4
        mat4f_uniformMap.put("ModelViewMat", VRenderSystem::getModelViewMatrix);
        mat4f_uniformMap.put("InverseModelViewMat", VRenderSystem::getModelViewMatrixInverse);
        mat4f_uniformMap.put("ProjMat", VRenderSystem::getProjectionMatrix);
        mat4f_uniformMap.put("InverseProjMat", VRenderSystem::getProjectionMatrixInverse);
        mat4f_uniformMap.put("MVP", VRenderSystem::getMVP);
        mat4f_uniformMap.put("TextureMat", VRenderSystem::getTextureMatrix);
        mat4f_uniformMap.put("shadowModelView", () -> VRenderSystem.shadowModelView);
        mat4f_uniformMap.put("shadowProjection", () -> VRenderSystem.shadowProjection);

        //Vec1i
        vec1i_uniformMap.put("EndPortalLayers", () -> 15);
        vec1i_uniformMap.put("isEyeInWater", () -> VRenderSystem.eyeInWater);
        vec1i_uniformMap.put("terrainShadowQuality", () -> Initializer.CONFIG.terrainShadowQuality);
        vec1i_uniformMap.put("terrainLightingQuality", () -> Initializer.CONFIG.terrainLightingQuality);
        vec1i_uniformMap.put("skyQuality", () -> Initializer.CONFIG.skyQuality);
        vec1i_uniformMap.put("waterQuality", () -> Initializer.CONFIG.waterQuality);
        vec1i_uniformMap.put("blockEmissiveTexturesEnabled", () -> Initializer.CONFIG.blockEmissiveTextures ? 1 : 0);
        vec1i_uniformMap.put("pbrEnabled", () -> net.vulkanmod.vulkshade.config.VulkShadeConfig.getInstance().isPBRenabled() ? 1 : 0);
        vec1i_uniformMap.put("pbrDebugMode", () -> Initializer.CONFIG.pbrDebugMode);
        vec1f_uniformMap.put("pbrNormalStrength", () -> Initializer.CONFIG.pbrNormalStrength);
        vec1f_uniformMap.put("pbrSpecularStrength", () -> Initializer.CONFIG.pbrSpecularStrength);
        vec1i_uniformMap.put("pomEnabled", () -> Initializer.CONFIG.pomEnabled ? 1 : 0);
        vec1f_uniformMap.put("pomHeightScale", () -> Initializer.CONFIG.pomHeightScale);


        //Vec1
        vec1f_uniformMap.put("FogStart", () -> VRenderSystem.getFogData().renderDistanceStart);
        vec1f_uniformMap.put("FogEnd", () -> VRenderSystem.getFogData().renderDistanceEnd);
        vec1f_uniformMap.put("FogEnvironmentalStart", () -> VRenderSystem.getFogData().environmentalStart);
        vec1f_uniformMap.put("FogEnvironmentalEnd", () -> VRenderSystem.getFogData().environmentalEnd);
        vec1f_uniformMap.put("FogRenderDistanceStart", () -> VRenderSystem.getFogData().renderDistanceStart);
        vec1f_uniformMap.put("FogRenderDistanceEnd", () -> VRenderSystem.getFogData().renderDistanceEnd);
        vec1f_uniformMap.put("FogSkyEnd", () -> VRenderSystem.getFogData().skyEnd);
        vec1f_uniformMap.put("FogCloudsEnd", () -> VRenderSystem.getFogData().cloudEnd);
        vec1f_uniformMap.put("LineWidth", RenderSystem::getShaderLineWidth);
        vec1f_uniformMap.put("AlphaCutout", () -> VRenderSystem.alphaCutout);
        vec1f_uniformMap.put("near", () -> 0.05f);
        vec1f_uniformMap.put("far", () -> MINECRAFT.options.getEffectiveRenderDistance() * 16.0f);
        vec1f_uniformMap.put("dayMoment", () -> VRenderSystem.skyDayMoment);
        vec1f_uniformMap.put("dayMixer", () -> VRenderSystem.skyDayMixer);
        vec1f_uniformMap.put("nightMixer", () -> VRenderSystem.skyNightMixer);
        vec1f_uniformMap.put("rainStrength", () -> VRenderSystem.skyRainStrength);
        vec1f_uniformMap.put("dayNightMix", () -> VRenderSystem.shadowDayNightMix);

        //Vec2
        vec2f_uniformMap.put("ScreenSize", VRenderSystem::getScreenSize);

        //Vec3
        vec3f_uniformMap.put("Light0_Direction", () -> VRenderSystem.lightDirection0);
        vec3f_uniformMap.put("Light1_Direction", () -> VRenderSystem.lightDirection1);
        vec3f_uniformMap.put("ModelOffset", () -> VRenderSystem.modelOffset);
        vec3f_uniformMap.put("ChunkOffset", () -> VRenderSystem.modelOffset);
        vec3f_uniformMap.put("shadowLightPosition", () -> VRenderSystem.shadowLightPosition);
        vec3f_uniformMap.put("cameraPosition", () -> VRenderSystem.skyCameraPosition);
        vec3f_uniformMap.put("sunPosition", () -> VRenderSystem.skySunDirection);

        //Vec4
        vec4f_uniformMap.put("ColorModulator", VRenderSystem::getShaderColor);
        vec4f_uniformMap.put("FogColor", VRenderSystem::getShaderFogColor);
        vec4f_uniformMap.put("fsrEasuCon0", VRenderSystem::getFsrEasuCon0);
        vec4f_uniformMap.put("fsrEasuCon1", VRenderSystem::getFsrEasuCon1);
        vec4f_uniformMap.put("fsrEasuCon2", VRenderSystem::getFsrEasuCon2);
        vec4f_uniformMap.put("fsrEasuCon3", VRenderSystem::getFsrEasuCon3);
        vec4f_uniformMap.put("fsrRcasCon", VRenderSystem::getFsrRcasCon);

        // Sky uniforms
        vec3f_uniformMap.put("SkyUpDirection", () -> VRenderSystem.skyUpDirection);
        vec3f_uniformMap.put("SkyLeftDirection", () -> VRenderSystem.skyLeftDirection);
        vec3f_uniformMap.put("SkyLookDirection", () -> VRenderSystem.skyLookDirection);
        vec3f_uniformMap.put("SkyCameraPosition", () -> VRenderSystem.skyCameraPosition);
        vec3f_uniformMap.put("SkySunDirection", () -> VRenderSystem.skySunDirection);
        vec3f_uniformMap.put("SkySunRightDirection", () -> VRenderSystem.skySunRightDirection);
        vec3f_uniformMap.put("SkySunUpDirection", () -> VRenderSystem.skySunUpDirection);
        vec3f_uniformMap.put("SkyMoonDirection", () -> VRenderSystem.skyMoonDirection);
        vec3f_uniformMap.put("SkyMoonRightDirection", () -> VRenderSystem.skyMoonRightDirection);
        vec3f_uniformMap.put("SkyMoonUpDirection", () -> VRenderSystem.skyMoonUpDirection);
        vec4f_uniformMap.put("SkySunsetColor", () -> VRenderSystem.skySunsetColor);
        vec1f_uniformMap.put("SkyCloudTime", () -> VRenderSystem.skyCloudTime);
        vec1f_uniformMap.put("SkyDayMoment", () -> VRenderSystem.skyDayMoment);
        vec1f_uniformMap.put("SkyDayMixer", () -> VRenderSystem.skyDayMixer);
        vec1f_uniformMap.put("SkyNightMixer", () -> VRenderSystem.skyNightMixer);
        vec1f_uniformMap.put("SkyRainStrength", () -> VRenderSystem.skyRainStrength);
        vec1i_uniformMap.put("SkyMoonPhase", () -> VRenderSystem.skyMoonPhase);
        vec1i_uniformMap.put("moonPhase", () -> VRenderSystem.skyMoonPhase);
        vec1f_uniformMap.put("SkyStarBrightness", () -> VRenderSystem.skyStarBrightness);

    }

    public static Supplier<MappedBuffer> getUniformSupplier(String type, String name) {
        return switch (type) {
            case "mat4" -> Uniforms.mat4f_uniformMap.get(name);
            case "vec4" -> Uniforms.vec4f_uniformMap.get(name);
            case "vec3" -> Uniforms.vec3f_uniformMap.get(name);
            case "vec2" -> Uniforms.vec2f_uniformMap.get(name);

            default -> null;
        };
    }
}
