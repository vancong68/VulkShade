package net.vulkanmod.vulkshade.effects;

import net.minecraft.client.renderer.fog.FogData;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Vector3f;

public class FogEffect {
    private float density = 0.01f;
    private float heightFalloff = 0.1f;
    private BiomeFogMode biomeMode = BiomeFogMode.BLENDED;
    private FogQuality quality = FogQuality.HIGH;

    public enum BiomeFogMode {
        VANILLA,
        BLENDED,
        DYNAMIC
    }

    public enum FogQuality {
        LOW,
        HIGH
    }

    public FogEffect() {
    }

    public void updateFromMinecraft(FogData fogData) {
        if (fogData == null) return;
        this.density = 1.0f / Math.max(fogData.environmentalEnd - fogData.environmentalStart, 0.001f);
    }

    public void setFogColor(float r, float g, float b, float a) {
        VRenderSystem.setShaderFogColor(r, g, b, a);
    }

    public void applyBiomeBlend(Vector3f biomeColor, float blendFactor) {
        MappedBuffer fogColor = VRenderSystem.getShaderFogColor();
        float existingR = fogColor.getFloat(0);
        float existingG = fogColor.getFloat(4);
        float existingB = fogColor.getFloat(8);
        float r = existingR * (1 - blendFactor) + biomeColor.x * blendFactor;
        float g = existingG * (1 - blendFactor) + biomeColor.y * blendFactor;
        float b = existingB * (1 - blendFactor) + biomeColor.z * blendFactor;
        fogColor.putFloat(0, r);
        fogColor.putFloat(4, g);
        fogColor.putFloat(8, b);
    }

    public UBO createFogUBO(int binding) {
        AlignedStruct.Builder builder = new AlignedStruct.Builder();
        builder.addUniformInfo(Uniform.createUniformInfo("float", "FogDensity", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("float", "FogHeightFalloff", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("float", "FogRenderDistance", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("float", "FogEnvironmentalStart", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("float", "FogEnvironmentalEnd", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("int", "FogQuality", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("int", "BiomeMode", 1));

        var fogUBO = builder.buildUBO(binding, -1);
        fogUBO.setUseGlobalBuffer(false);

        return fogUBO;
    }

    public float getDensity() { return density; }
    public void setDensity(float density) { this.density = density; }
    public float getHeightFalloff() { return heightFalloff; }
    public void setHeightFalloff(float falloff) { this.heightFalloff = falloff; }
    public BiomeFogMode getBiomeMode() { return biomeMode; }
    public void setBiomeMode(BiomeFogMode mode) { this.biomeMode = mode; }
    public FogQuality getQuality() { return quality; }
    public void setQuality(FogQuality quality) { this.quality = quality; }
}
