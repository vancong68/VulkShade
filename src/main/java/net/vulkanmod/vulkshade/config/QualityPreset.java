package net.vulkanmod.vulkshade.config;

import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Consumer;

public enum QualityPreset {
    LOW("Low", config -> {
        config.fsrEnabled = true;
        config.fsrInternalScale = 50;
        config.ambientOcclusion = 0;
        config.terrainShadowQuality = 0;
        config.terrainLightingQuality = 0;
        config.skyQuality = 0;
        config.waterQuality = 2;
        config.volumetricLighting = false;
        config.blockEmissiveTextures = false;
        config.textureAnimations = false;
        config.uniqueOpaqueLayer = true;
        config.advCulling = 2;
    }),

    MEDIUM("Medium", config -> {
        config.fsrEnabled = false;
        config.ambientOcclusion = 0;
        config.terrainShadowQuality = 0;
        config.terrainLightingQuality = 1;
        config.skyQuality = 1;
        config.waterQuality = 1;
        config.volumetricLighting = false;
        config.blockEmissiveTextures = true;
        config.textureAnimations = true;
        config.uniqueOpaqueLayer = true;
        config.advCulling = 1;
    }),

    HIGH("High", config -> {
        config.fsrEnabled = false;
        config.ambientOcclusion = 0;
        config.terrainShadowQuality = 1;
        config.terrainLightingQuality = 2;
        config.skyQuality = 2;
        config.waterQuality = 0;
        config.volumetricLighting = true;
        config.blockEmissiveTextures = true;
        config.textureAnimations = true;
        config.uniqueOpaqueLayer = true;
        config.advCulling = 1;
    }),

    ULTRA("Ultra", config -> {
        config.fsrEnabled = false;
        config.ambientOcclusion = 1;
        config.terrainShadowQuality = 2;
        config.terrainLightingQuality = 2;
        config.skyQuality = 3;
        config.waterQuality = 0;
        config.volumetricLighting = true;
        config.blockEmissiveTextures = true;
        config.textureAnimations = true;
        config.uniqueOpaqueLayer = false;
        config.advCulling = 0;
    }),

    CUSTOM("Custom", config -> {
    });

    private static final Logger LOGGER = LogManager.getLogger("VulkShade-QualityPreset");

    private final String displayName;
    private final Consumer<Config> applier;

    QualityPreset(String displayName, Consumer<Config> applier) {
        this.displayName = displayName;
        this.applier = applier;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void apply() {
        Config config = Initializer.CONFIG;
        applier.accept(config);
        config.write();
        LOGGER.info("Applied quality preset: {}", displayName);
    }

    public boolean isActive() {
        Config config = Initializer.CONFIG;
        try {
            Config testConfig = new Config();
            applier.accept(testConfig);
            return config.ambientOcclusion == testConfig.ambientOcclusion
                && config.terrainShadowQuality == testConfig.terrainShadowQuality
                && config.terrainLightingQuality == testConfig.terrainLightingQuality
                && config.skyQuality == testConfig.skyQuality
                && config.waterQuality == testConfig.waterQuality
                && config.volumetricLighting == testConfig.volumetricLighting
                && config.blockEmissiveTextures == testConfig.blockEmissiveTextures;
        } catch (Exception e) {
            return false;
        }
    }

    public static QualityPreset detectCurrent() {
        for (QualityPreset preset : values()) {
            if (preset.isActive() && preset != CUSTOM) return preset;
        }
        return CUSTOM;
    }
}
