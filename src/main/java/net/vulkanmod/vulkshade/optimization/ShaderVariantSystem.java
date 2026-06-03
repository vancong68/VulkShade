package net.vulkanmod.vulkshade.optimization;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkshade.config.QualityPreset;
import net.vulkanmod.vulkshade.config.ShaderQuality;
import net.vulkanmod.vulkshade.config.VulkShadeConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ShaderVariantSystem {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-ShaderVariant");
    private static ShaderVariantSystem INSTANCE;

    private final Map<String, ShaderVariant> variantMap = new ConcurrentHashMap<>();
    private QualityLevel activeQuality = QualityLevel.MEDIUM;
    private boolean asyncCompilationEnabled = true;

    private int overriddenFeatures = 0;
    private int enabledFeatures = 0xfFF;

    public enum QualityLevel {
        LOW(0),
        MEDIUM(1),
        HIGH(2),
        ULTRA(3);

        final int level;
        QualityLevel(int level) { this.level = level; }
        public int getLevel() { return level; }

        public static QualityLevel fromPreset(QualityPreset preset) {
            return switch (preset) {
                case LOW -> LOW;
                case MEDIUM -> MEDIUM;
                case HIGH -> HIGH;
                case ULTRA -> ULTRA;
                default -> MEDIUM;
            };
        }

        public static QualityLevel fromShaderQuality(ShaderQuality quality) {
            return switch (quality) {
                case LOW -> LOW;
                case MEDIUM -> MEDIUM;
                case HIGH -> HIGH;
            };
        }
    }

    public enum ShaderFeature {
        SSAO(1 << 0),
        BLOOM(1 << 1),
        PBR(1 << 2),
        SHADOW(1 << 3),
        FOG(1 << 3),
        WATER_REFLECT(1 << 4),
        EMISSIVE(1 << 5),
        VOLUMETRIC(1 << 6),
        VOX_LOD(1 << 7),
        MOTION_BLUR(1 << 8),
        LENS_FLARE(1 << 9);

        final int bit;
        ShaderFeature(int bit) { this.bit = bit; }
        public int getBit() { return bit; }
    }

    public ShaderVariantSystem() {
    }

    public static ShaderVariantSystem getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ShaderVariantSystem();
        }
        return INSTANCE;
    }

    public void initialize() {
        updateQualityFromConfig();
        LOGGER.info("ShaderVariantSystem initialized: quality={}, async={}",
            activeQuality, asyncCompilationEnabled);
    }

    public void updateQualityFromConfig() {
        QualityPreset preset = VulkShadeConfig.getInstance().getActivePreset();
        ShaderQuality sq = ShaderQuality.detectCurrent();
        this.activeQuality = QualityLevel.fromShaderQuality(sq);
        LOGGER.info("ShaderVariantSystem quality updated: preset={}, shaderQuality={}, activeLevel={}",
            preset, sq, activeQuality);
    }

    public void updateQualityFromShaderQuality(ShaderQuality quality) {
        this.activeQuality = QualityLevel.fromShaderQuality(quality);
        LOGGER.info("ShaderVariantSystem quality set from ShaderQuality: {}", activeQuality);
    }

    public String getVariantDefines(QualityLevel quality) {
        StringBuilder sb = new StringBuilder();
        int features = getFeatureMask(quality);

        sb.append("#define QUALITY_LEVEL ").append(quality.getLevel()).append("\n");

        if ((features & ShaderFeature.SSAO.getBit()) != 0)
            sb.append("#define FEATURE_SSAO\n");
        if ((features & ShaderFeature.BLOOM.getBit()) != 0)
            sb.append("#define FEATURE_BLOOM\n");

        if ((features & ShaderFeature.PBR.getBit()) != 0)
            sb.append("#define FEATURE_PBR\n");
        if ((features & ShaderFeature.SHADOW.getBit()) != 0)
            sb.append("#define FEATURE_SHADOW\n");
        if ((features & ShaderFeature.FOG.getBit()) != 0)
            sb.append("#define FEATURE_FOG\n");
        if ((features & ShaderFeature.EMISSIVE.getBit()) != 0)
            sb.append("#define FEATURE_EMISSIVE\n");
        if ((features & ShaderFeature.VOLUMETRIC.getBit()) != 0)
            sb.append("#define FEATURE_VOLUMETRIC\n");
        if ((features & ShaderFeature.VOX_LOD.getBit()) != 0)
            sb.append("#define FEATURE_VOX_LOD\n");
        if ((features & ShaderFeature.MOTION_BLUR.getBit()) != 0)
            sb.append("#define FEATURE_MOTION_BLUR\n");
        if ((features & ShaderFeature.LENS_FLARE.getBit()) != 0)
            sb.append("#define FEATURE_LENS_FLARE\n");

        return sb.toString();
    }

    public String getVariantDefines() {
        return getVariantDefines(activeQuality);
    }

    public int getFeatureMask() {
        return getEffectiveFeatureMask();
    }

    public int getDefaultFeatureMask() {
        return getFeatureMask(activeQuality);
    }

    public int getFeatureMask(QualityLevel quality) {
        return switch (quality) {
            case LOW -> ShaderFeature.FOG.getBit() | ShaderFeature.EMISSIVE.getBit()
                | ShaderFeature.VOX_LOD.getBit();
            case MEDIUM -> ShaderFeature.FOG.getBit() | ShaderFeature.EMISSIVE.getBit()
                | ShaderFeature.SSAO.getBit() | ShaderFeature.SHADOW.getBit()
                | ShaderFeature.VOX_LOD.getBit();
            case HIGH -> ShaderFeature.FOG.getBit() | ShaderFeature.EMISSIVE.getBit()
                | ShaderFeature.SSAO.getBit() | ShaderFeature.SHADOW.getBit()
                | ShaderFeature.BLOOM.getBit()
                | ShaderFeature.VOX_LOD.getBit();
            case ULTRA -> ShaderFeature.FOG.getBit() | ShaderFeature.EMISSIVE.getBit()
                | ShaderFeature.SSAO.getBit() | ShaderFeature.SHADOW.getBit()
                | ShaderFeature.BLOOM.getBit()
                | ShaderFeature.PBR.getBit() | ShaderFeature.VOLUMETRIC.getBit()
                | ShaderFeature.VOX_LOD.getBit()
                | ShaderFeature.MOTION_BLUR.getBit() | ShaderFeature.LENS_FLARE.getBit();
        };
    }

    public int getSSAOSampleCount() {
        return switch (activeQuality) {
            case LOW -> 16;
            case MEDIUM -> 32;
            case HIGH -> 48;
            case ULTRA -> 64;
        };
    }

    public int getShadowResolution() {
        return switch (activeQuality) {
            case LOW -> 512;
            case MEDIUM -> 1024;
            case HIGH -> 2048;
            case ULTRA -> 4096;
        };
    }

    public int getBloomMipLevels() {
        return switch (activeQuality) {
            case LOW -> 3;
            case MEDIUM -> 4;
            case HIGH -> 5;
            case ULTRA -> 5;
        };
    }

    public int getCascadeCount() {
        return switch (activeQuality) {
            case LOW, MEDIUM -> 2;
            case HIGH, ULTRA -> 4;
        };
    }

    public boolean isFeatureEnabled(ShaderFeature feature) {
        return (getEffectiveFeatureMask() & feature.getBit()) != 0;
    }

    public void setFeatureEnabled(ShaderFeature feature, boolean enabled) {
        int bit = feature.getBit();
        overriddenFeatures |= bit;
        if (enabled) {
            enabledFeatures |= bit;
        } else {
            enabledFeatures &= ~bit;
        }
    }

    public void clearFeatureOverride(ShaderFeature feature) {
        overriddenFeatures &= ~feature.getBit();
    }

    public void clearAllFeatureOverrides() {
        overriddenFeatures = 0;
        enabledFeatures = 0xfFF;
    }

    public int getEffectiveFeatureMask() {
        int base = getFeatureMask(activeQuality);
        int effective = base;
        for (ShaderFeature feature : ShaderFeature.values()) {
            if ((overriddenFeatures & feature.getBit()) != 0) {
                if ((enabledFeatures & feature.getBit()) != 0) {
                    effective |= feature.getBit();
                } else {
                    effective &= ~feature.getBit();
                }
            }
        }
        return effective;
    }

    public boolean isAsyncCompilationEnabled() { return asyncCompilationEnabled; }
    public void setAsyncCompilationEnabled(boolean enabled) { this.asyncCompilationEnabled = enabled; }
    public QualityLevel getActiveQuality() { return activeQuality; }
    public void setActiveQuality(QualityLevel quality) {
        this.activeQuality = quality;
        LOGGER.info("Shader quality set to: {}", quality);
    }

    public static class ShaderVariant {
        final String name;
        final QualityLevel quality;
        final int featureMask;
        volatile boolean compiled = false;
        volatile Object spirvData = null;
        volatile Throwable compileError = null;

        ShaderVariant(String name, QualityLevel quality, int featureMask) {
            this.name = name;
            this.quality = quality;
            this.featureMask = featureMask;
        }

        public boolean isCompiled() { return compiled; }
        public boolean hasError() { return compileError != null; }
        public Throwable getError() { return compileError; }
    }
}
