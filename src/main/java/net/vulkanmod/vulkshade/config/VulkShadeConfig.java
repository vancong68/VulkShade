package net.vulkanmod.vulkshade.config;

import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkshade.effects.*;
import net.vulkanmod.vulkshade.optimization.ChunkBatchManager;
import net.vulkanmod.vulkshade.optimization.CullingSystemUpgrade;
import net.vulkanmod.vulkshade.optimization.PerformanceScaler;
import net.vulkanmod.vulkshade.optimization.ShaderVariantSystem;
import net.vulkanmod.vulkshade.render.VoxyLODManager;
import net.vulkanmod.vulkshade.shader.ShaderManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class VulkShadeConfig {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-Config");

    private static VulkShadeConfig INSTANCE;

    private QualityPreset activePreset = QualityPreset.detectCurrent();

    private boolean shaderHotReload = true;
    private boolean pipelineCacheOnDisk = true;

    // Quality settings
    private FogEffect.BiomeFogMode biomeFogMode = FogEffect.BiomeFogMode.BLENDED;
    private FogEffect.FogQuality fogQuality = FogEffect.FogQuality.HIGH;
    private WaterShader.WaterQuality waterQuality = WaterShader.WaterQuality.MEDIUM;
    private ShadowMapEffect.ShadowQuality shadowQuality = ShadowMapEffect.ShadowQuality.MEDIUM;
    private PBRDeferredLighting.PBRQuality pbrQuality = PBRDeferredLighting.PBRQuality.MEDIUM;

    // Performance controls
    private boolean chunkMergingEnabled = true;
    private int maxBatchSize = 128;
    private boolean occlusionCulling = false;
    private boolean hizCulling = false;
    private boolean lodCulling = true;
    private int targetFPS = 0;
    private boolean asyncShaderCompile = true;
    private int ssaoSampleCount = 32;
    private float bloomIntensity = 0.4f;
    private float motionBlurStrength = 0.5f;
    private float shadowBias = 0.005f;
    private int shadowResolution = 1024;
    private int cascadeCount = 2;

    // Performance scaler
    private boolean adaptivePerformance = true;
    private boolean dynamicRenderDistance = true;
    private boolean framePacingEnabled = true;
    private boolean chunkBatchRendering = true;

    private VulkShadeConfig() {
    }

    public static VulkShadeConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new VulkShadeConfig();
        }
        return INSTANCE;
    }

    public void applyPreset(QualityPreset preset) {
        this.activePreset = preset;
        preset.apply();

        boolean isHighEnd = preset == QualityPreset.ULTRA || preset == QualityPreset.HIGH;
        boolean isUltra = preset == QualityPreset.ULTRA;

        setSSAOEnabled(isHighEnd);
        setBloomEnabled(isUltra);
        setPBRenabled(isUltra);
        setEnhancedShadows(isHighEnd);
        this.chunkMergingEnabled = true;
        this.occlusionCulling = isUltra;
        this.hizCulling = isUltra;
        this.lodCulling = true;
        this.asyncShaderCompile = true;

        switch (preset) {
            case ULTRA -> {
                waterQuality = WaterShader.WaterQuality.ULTRA;
                shadowQuality = ShadowMapEffect.ShadowQuality.ULTRA;
                fogQuality = FogEffect.FogQuality.HIGH;
                pbrQuality = PBRDeferredLighting.PBRQuality.HIGH;
                ssaoSampleCount = 64;
                bloomIntensity = 1.0f;
                shadowResolution = 4096;
                cascadeCount = 4;
                maxBatchSize = 256;
            }
            case HIGH -> {
                waterQuality = WaterShader.WaterQuality.HIGH;
                shadowQuality = ShadowMapEffect.ShadowQuality.HIGH;
                fogQuality = FogEffect.FogQuality.HIGH;
                pbrQuality = PBRDeferredLighting.PBRQuality.MEDIUM;
                ssaoSampleCount = 48;
                bloomIntensity = 0.7f;
                shadowResolution = 2048;
                cascadeCount = 3;
                maxBatchSize = 128;
            }
            case MEDIUM -> {
                waterQuality = WaterShader.WaterQuality.MEDIUM;
                shadowQuality = ShadowMapEffect.ShadowQuality.MEDIUM;
                fogQuality = FogEffect.FogQuality.HIGH;
                pbrQuality = PBRDeferredLighting.PBRQuality.LOW;
                ssaoSampleCount = 32;
                bloomIntensity = 0.5f;
                shadowResolution = 1024;
                cascadeCount = 2;
                maxBatchSize = 128;
            }
            case LOW -> {
                waterQuality = WaterShader.WaterQuality.LOW;
                shadowQuality = ShadowMapEffect.ShadowQuality.LOW;
                fogQuality = FogEffect.FogQuality.LOW;
                pbrQuality = PBRDeferredLighting.PBRQuality.LOW;
                ssaoSampleCount = 16;
                bloomIntensity = 0.3f;
                shadowResolution = 512;
                cascadeCount = 2;
                maxBatchSize = 64;
            }
        }

        ShaderVariantSystem.getInstance().updateQualityFromConfig();
        if (ChunkBatchManager.getInstance() != null) {
            ChunkBatchManager.getInstance().setMaxBatchSize(maxBatchSize);
        }

        if (PerformanceScaler.getInstance() != null) {
            PerformanceScaler.getInstance().onQualityPresetChanged();
        }

        LOGGER.info("VulkShade config updated to preset: {} (FPS target: {})",
            preset.getDisplayName(), targetFPS > 0 ? targetFPS : "unlimited");
    }

    public QualityPreset getActivePreset() { return activePreset; }

    // ========== Shader Settings ==========
    public boolean isShaderHotReload() { return shaderHotReload; }
    public void setShaderHotReload(boolean enabled) { this.shaderHotReload = enabled; }
    public boolean isPipelineCacheOnDisk() { return pipelineCacheOnDisk; }
    public void setPipelineCacheOnDisk(boolean enabled) { this.pipelineCacheOnDisk = enabled; }

    // ========== Effect Toggles ==========
    public boolean isSSAOEnabled() { return isFeatureEnabled(ShaderVariantSystem.ShaderFeature.SSAO); }
    public void setSSAOEnabled(boolean enabled) {
        setFeatureEnabled(ShaderVariantSystem.ShaderFeature.SSAO, enabled, () -> Initializer.CONFIG.featureSSAO = enabled);
    }
    public boolean isBloomEnabled() { return isFeatureEnabled(ShaderVariantSystem.ShaderFeature.BLOOM); }
    public void setBloomEnabled(boolean enabled) {
        setFeatureEnabled(ShaderVariantSystem.ShaderFeature.BLOOM, enabled, () -> Initializer.CONFIG.featureBloom = enabled);
    }
    public boolean isPBRenabled() { return isFeatureEnabled(ShaderVariantSystem.ShaderFeature.PBR); }
    public void setPBRenabled(boolean enabled) {
        setFeatureEnabled(ShaderVariantSystem.ShaderFeature.PBR, enabled, () -> Initializer.CONFIG.featurePBR = enabled);
    }
    public boolean isEnhancedShadows() { return isFeatureEnabled(ShaderVariantSystem.ShaderFeature.SHADOW); }
    public void setEnhancedShadows(boolean enabled) {
        setFeatureEnabled(ShaderVariantSystem.ShaderFeature.SHADOW, enabled, () -> Initializer.CONFIG.featureShadows = enabled);
    }
    public boolean isFogEnabled() { return isFeatureEnabled(ShaderVariantSystem.ShaderFeature.FOG); }
    public void setFogEnabled(boolean enabled) {
        setFeatureEnabled(ShaderVariantSystem.ShaderFeature.FOG, enabled, () -> Initializer.CONFIG.featureFog = enabled);
    }
    public boolean isWaterReflectEnabled() { return isFeatureEnabled(ShaderVariantSystem.ShaderFeature.WATER_REFLECT); }
    public void setWaterReflectEnabled(boolean enabled) {
        setFeatureEnabled(ShaderVariantSystem.ShaderFeature.WATER_REFLECT, enabled, () -> Initializer.CONFIG.featureWaterReflect = enabled);
    }
    public boolean isEmissiveEnabled() { return isFeatureEnabled(ShaderVariantSystem.ShaderFeature.EMISSIVE); }
    public void setEmissiveEnabled(boolean enabled) {
        setFeatureEnabled(ShaderVariantSystem.ShaderFeature.EMISSIVE, enabled, () -> Initializer.CONFIG.featureEmissive = enabled);
    }
    public boolean isVolumetricEnabled() { return isFeatureEnabled(ShaderVariantSystem.ShaderFeature.VOLUMETRIC); }
    public void setVolumetricEnabled(boolean enabled) {
        setFeatureEnabled(ShaderVariantSystem.ShaderFeature.VOLUMETRIC, enabled, () -> Initializer.CONFIG.featureVolumetric = enabled);
    }
    public boolean isMotionBlurEnabled() { return isFeatureEnabled(ShaderVariantSystem.ShaderFeature.MOTION_BLUR); }
    public void setMotionBlurEnabled(boolean enabled) {
        setFeatureEnabled(ShaderVariantSystem.ShaderFeature.MOTION_BLUR, enabled, () -> Initializer.CONFIG.featureMotionBlur = enabled);
    }
    public boolean isLensFlareEnabled() { return isFeatureEnabled(ShaderVariantSystem.ShaderFeature.LENS_FLARE); }
    public void setLensFlareEnabled(boolean enabled) {
        setFeatureEnabled(ShaderVariantSystem.ShaderFeature.LENS_FLARE, enabled, () -> Initializer.CONFIG.featureLensFlare = enabled);
    }

    public void loadFeatureOverridesFromPersistedConfig() {
        ShaderVariantSystem svs = ShaderVariantSystem.getInstance();
        Config cfg = Initializer.CONFIG;
        setFeatureOverrideFromConfig(svs, ShaderVariantSystem.ShaderFeature.SSAO, cfg.featureSSAO);
        setFeatureOverrideFromConfig(svs, ShaderVariantSystem.ShaderFeature.BLOOM, cfg.featureBloom);
        setFeatureOverrideFromConfig(svs, ShaderVariantSystem.ShaderFeature.PBR, cfg.featurePBR);
        setFeatureOverrideFromConfig(svs, ShaderVariantSystem.ShaderFeature.SHADOW, cfg.featureShadows);
        setFeatureOverrideFromConfig(svs, ShaderVariantSystem.ShaderFeature.FOG, cfg.featureFog);
        setFeatureOverrideFromConfig(svs, ShaderVariantSystem.ShaderFeature.WATER_REFLECT, cfg.featureWaterReflect);
        setFeatureOverrideFromConfig(svs, ShaderVariantSystem.ShaderFeature.EMISSIVE, cfg.featureEmissive);
        setFeatureOverrideFromConfig(svs, ShaderVariantSystem.ShaderFeature.VOLUMETRIC, cfg.featureVolumetric);
        setFeatureOverrideFromConfig(svs, ShaderVariantSystem.ShaderFeature.MOTION_BLUR, cfg.featureMotionBlur);
        setFeatureOverrideFromConfig(svs, ShaderVariantSystem.ShaderFeature.LENS_FLARE, cfg.featureLensFlare);
        LOGGER.info("Loaded per-feature overrides from persisted config");
    }

    private static void setFeatureOverrideFromConfig(ShaderVariantSystem svs, ShaderVariantSystem.ShaderFeature feature, boolean enabled) {
        svs.setFeatureEnabled(feature, enabled);
    }

    private boolean isFeatureEnabled(ShaderVariantSystem.ShaderFeature feature) {
        return ShaderVariantSystem.getInstance().isFeatureEnabled(feature);
    }

    private void setFeatureEnabled(ShaderVariantSystem.ShaderFeature feature, boolean enabled, Runnable persist) {
        ShaderVariantSystem.getInstance().setFeatureEnabled(feature, enabled);
        persist.run();
        Initializer.CONFIG.write();
        LOGGER.info("{}: {}", feature.name(), enabled ? "enabled" : "disabled");
    }

    // ========== Quality Settings ==========
    public FogEffect.BiomeFogMode getBiomeFogMode() { return biomeFogMode; }
    public void setBiomeFogMode(FogEffect.BiomeFogMode mode) { this.biomeFogMode = mode; }
    public FogEffect.FogQuality getFogQuality() { return fogQuality; }
    public void setFogQuality(FogEffect.FogQuality quality) { this.fogQuality = quality; }
    public WaterShader.WaterQuality getWaterQuality() { return waterQuality; }
    public void setWaterQuality(WaterShader.WaterQuality quality) { this.waterQuality = quality; }
    public ShadowMapEffect.ShadowQuality getShadowQuality() { return shadowQuality; }
    public void setShadowQuality(ShadowMapEffect.ShadowQuality quality) {
        this.shadowQuality = quality;
        setEnhancedShadows(quality != ShadowMapEffect.ShadowQuality.OFF);
        LOGGER.info("Shadow quality set to: {}", quality);
    }
    public PBRDeferredLighting.PBRQuality getPBRQuality() { return pbrQuality; }
    public void setPBRQuality(PBRDeferredLighting.PBRQuality quality) {
        this.pbrQuality = quality;
        setPBRenabled(quality != PBRDeferredLighting.PBRQuality.LOW);
        LOGGER.info("PBR quality set to: {}", quality);
    }

    // ========== Performance Controls ==========
    public boolean isChunkMergingEnabled() { return chunkMergingEnabled; }
    public void setChunkMergingEnabled(boolean enabled) {
        this.chunkMergingEnabled = enabled;
        if (ChunkBatchManager.getInstance() != null)
            ChunkBatchManager.getInstance().setMergeEnabled(enabled);
        LOGGER.info("Chunk merging {}", enabled ? "enabled" : "disabled");
    }
    public int getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(int size) {
        this.maxBatchSize = Math.max(16, size);
        if (ChunkBatchManager.getInstance() != null)
            ChunkBatchManager.getInstance().setMaxBatchSize(this.maxBatchSize);
    }
    public boolean isOcclusionCulling() { return occlusionCulling; }
    public void setOcclusionCulling(boolean enabled) {
        this.occlusionCulling = enabled;
        if (CullingSystemUpgrade.getInstance() != null)
            CullingSystemUpgrade.getInstance().setOcclusionCullingEnabled(enabled);
        LOGGER.info("Occlusion culling {}", enabled ? "enabled" : "disabled");
    }
    public boolean isHizCulling() { return hizCulling; }
    public void setHizCulling(boolean enabled) {
        this.hizCulling = enabled;
        if (CullingSystemUpgrade.getInstance() != null)
            CullingSystemUpgrade.getInstance().setHizEnabled(enabled);
        LOGGER.info("Hi-Z culling {}", enabled ? "enabled" : "disabled");
    }
    public boolean isLodCulling() { return lodCulling; }
    public void setLodCulling(boolean enabled) {
        this.lodCulling = enabled;
        if (CullingSystemUpgrade.getInstance() != null)
            CullingSystemUpgrade.getInstance().getLodConfig().enabled = enabled;
    }
    public int getTargetFPS() { return targetFPS; }
    public void setTargetFPS(int fps) {
        this.targetFPS = Math.max(0, fps);
        if (this.targetFPS > 0) {
            boolean vsync = this.targetFPS <= 60;
            Vulkan.setVsync(vsync);
        }
        LOGGER.info("FPS target set to: {}", this.targetFPS > 0 ? this.targetFPS : "unlimited");
    }
    public boolean isAsyncShaderCompile() { return asyncShaderCompile; }
    public void setAsyncShaderCompile(boolean enabled) { this.asyncShaderCompile = enabled; }

    // ========== Performance Scaler ==========
    public boolean isAdaptivePerformance() {
        return Initializer.CONFIG.adaptivePerformance;
    }
    public void setAdaptivePerformance(boolean enabled) {
        Initializer.CONFIG.adaptivePerformance = enabled;
        Initializer.CONFIG.write();
        if (PerformanceScaler.getInstance() != null)
            PerformanceScaler.getInstance().setAdaptiveMode(enabled);
        LOGGER.info("Adaptive performance: {}", enabled ? "enabled" : "disabled");
    }
    public boolean isDynamicRenderDistance() {
        return Initializer.CONFIG.dynamicRenderDistance;
    }
    public void setDynamicRenderDistance(boolean enabled) {
        Initializer.CONFIG.dynamicRenderDistance = enabled;
        Initializer.CONFIG.write();
        if (PerformanceScaler.getInstance() != null)
            PerformanceScaler.getInstance().setDynamicRenderDistance(enabled);
        LOGGER.info("Dynamic render distance: {}", enabled ? "enabled" : "disabled");
    }
    public boolean isFramePacingEnabled() {
        return Initializer.CONFIG.framePacing;
    }
    public void setFramePacingEnabled(boolean enabled) {
        Initializer.CONFIG.framePacing = enabled;
        Initializer.CONFIG.write();
        if (PerformanceScaler.getInstance() != null)
            PerformanceScaler.getInstance().setFramePacingEnabled(enabled);
        LOGGER.info("Frame pacing: {}", enabled ? "enabled" : "disabled");
    }
    public int getFramePacingTarget() {
        return Initializer.CONFIG.framePacingTarget;
    }
    public void setFramePacingTarget(int fps) {
        Initializer.CONFIG.framePacingTarget = fps;
        Initializer.CONFIG.write();
        if (PerformanceScaler.getInstance() != null)
            PerformanceScaler.getInstance().setTargetFPS(fps);
        LOGGER.info("Frame pacing target: {} FPS", fps);
    }
    public boolean isChunkBatchRendering() {
        return Initializer.CONFIG.chunkBatchRendering;
    }
    public void setChunkBatchRendering(boolean enabled) {
        Initializer.CONFIG.chunkBatchRendering = enabled;
        Initializer.CONFIG.write();
        setChunkMergingEnabled(enabled);
        LOGGER.info("Chunk batch rendering: {}", enabled ? "enabled" : "disabled");
    }

    // ========== Voxy LOD ==========
    public boolean isVoxyLODEnabled() {
        return Initializer.CONFIG.voxyLODEnabled;
    }
    public void setVoxyLODEnabled(boolean enabled) {
        Initializer.CONFIG.voxyLODEnabled = enabled;
        Initializer.CONFIG.write();
        if (VoxyLODManager.getInstance() != null)
            VoxyLODManager.getInstance().setEnabled(enabled);
        LOGGER.info("Voxy LOD: {}", enabled ? "enabled" : "disabled");
    }
    public VoxyLODManager.LODQuality getLODQuality() {
        return switch (Initializer.CONFIG.voxyLODQuality) {
            case 0 -> VoxyLODManager.LODQuality.LOW;
            case 1 -> VoxyLODManager.LODQuality.MEDIUM;
            case 2 -> VoxyLODManager.LODQuality.HIGH;
            default -> VoxyLODManager.LODQuality.MEDIUM;
        };
    }
    public void setLODQuality(VoxyLODManager.LODQuality quality) {
        int idx = quality == VoxyLODManager.LODQuality.LOW ? 0
            : quality == VoxyLODManager.LODQuality.HIGH ? 2 : 1;
        Initializer.CONFIG.voxyLODQuality = idx;
        Initializer.CONFIG.write();
        if (VoxyLODManager.getInstance() != null)
            VoxyLODManager.getInstance().setQuality(quality);
        LOGGER.info("Voxy LOD quality set to: {}", quality.name());
    }
    public int getMaxLODViewDistance() {
        return Initializer.CONFIG.voxyLODMaxDistance;
    }
    public void setMaxLODViewDistance(int distance) {
        Initializer.CONFIG.voxyLODMaxDistance = distance;
        Initializer.CONFIG.write();
        if (VoxyLODManager.getInstance() != null)
            VoxyLODManager.getInstance().setMaxViewDistance(distance);
        LOGGER.info("Voxy LOD max distance: {}", distance);
    }

    // ========== Fine-tuning ==========
    public int getSSAOSampleCount() { return ssaoSampleCount; }
    public void setSSAOSampleCount(int count) {
        this.ssaoSampleCount = Math.max(8, Math.min(128, count));
    }
    public float getBloomIntensity() { return bloomIntensity; }
    public void setBloomIntensity(float intensity) {
        this.bloomIntensity = Math.max(0.0f, Math.min(2.0f, intensity));
    }
    public float getMotionBlurStrength() { return motionBlurStrength; }
    public void setMotionBlurStrength(float strength) {
        this.motionBlurStrength = Math.max(0.0f, Math.min(0.95f, strength));
    }
    public float getShadowBias() { return shadowBias; }
    public void setShadowBias(float bias) { this.shadowBias = bias; }
    public int getShadowResolution() { return shadowResolution; }
    public void setShadowResolution(int res) {
        this.shadowResolution = Math.max(256, Math.min(8192, res));
    }
    public int getCascadeCount() { return cascadeCount; }
    public void setCascadeCount(int count) {
        this.cascadeCount = Math.max(1, Math.min(4, count));
    }
}
