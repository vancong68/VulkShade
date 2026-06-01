package net.vulkanmod.vulkshade.config;

import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.vulkshade.effects.*;
import net.vulkanmod.vulkshade.optimization.ShaderVariantSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Consumer;

public enum ShaderQuality {
    LOW("Low", config -> {
        config.shaderQuality = 0;
    },
    vcfg -> {
        vcfg.setSSAOEnabled(false);
        vcfg.setBloomEnabled(false);
        vcfg.setPBRenabled(false);
        vcfg.setEnhancedShadows(false);
        vcfg.setWaterQuality(WaterShader.WaterQuality.LOW);
        vcfg.setShadowQuality(ShadowMapEffect.ShadowQuality.OFF);
        vcfg.setFogQuality(FogEffect.FogQuality.LOW);
        vcfg.setSSAOSampleCount(8);
        vcfg.setBloomIntensity(0.0f);
        vcfg.setShadowResolution(0);
        vcfg.setCascadeCount(1);
    }),

    MEDIUM("Medium", config -> {
        config.shaderQuality = 1;
    },
    vcfg -> {
        vcfg.setSSAOEnabled(true);
        vcfg.setBloomEnabled(true);
        vcfg.setPBRenabled(true);
        vcfg.setEnhancedShadows(true);
        vcfg.setWaterQuality(WaterShader.WaterQuality.MEDIUM);
        vcfg.setShadowQuality(ShadowMapEffect.ShadowQuality.MEDIUM);
        vcfg.setFogQuality(FogEffect.FogQuality.HIGH);
        vcfg.setSSAOSampleCount(32);
        vcfg.setBloomIntensity(0.5f);
        vcfg.setShadowResolution(1024);
        vcfg.setCascadeCount(2);
    }),

    HIGH("High", config -> {
        config.shaderQuality = 2;
    },
    vcfg -> {
        vcfg.setSSAOEnabled(true);
        vcfg.setBloomEnabled(true);
        vcfg.setPBRenabled(true);
        vcfg.setEnhancedShadows(true);
        vcfg.setWaterQuality(WaterShader.WaterQuality.HIGH);
        vcfg.setShadowQuality(ShadowMapEffect.ShadowQuality.HIGH);
        vcfg.setFogQuality(FogEffect.FogQuality.HIGH);
        vcfg.setSSAOSampleCount(64);
        vcfg.setBloomIntensity(0.8f);
        vcfg.setShadowResolution(2048);
        vcfg.setCascadeCount(4);
    });

    private static final Logger LOGGER = LogManager.getLogger("VulkShade-ShaderQuality");

    private final String displayName;
    private final Consumer<Config> configApplier;
    private final Consumer<VulkShadeConfig> effectApplier;

    ShaderQuality(String displayName, Consumer<Config> configApplier, Consumer<VulkShadeConfig> effectApplier) {
        this.displayName = displayName;
        this.configApplier = configApplier;
        this.effectApplier = effectApplier;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void apply() {
        Config baseConfig = Initializer.CONFIG;
        configApplier.accept(baseConfig);
        baseConfig.write();

        VulkShadeConfig vcfg = VulkShadeConfig.getInstance();
        effectApplier.accept(vcfg);

        ShaderVariantSystem.getInstance().updateQualityFromShaderQuality(this);

        LOGGER.info("Shader quality preset applied: {} (SSAO={}, Bloom={}, PBR={}, Shadows[{}cs={}])",
            displayName,
            vcfg.isSSAOEnabled(),
            vcfg.isBloomEnabled(),
            vcfg.isPBRenabled(),
            vcfg.getCascadeCount(),
            vcfg.getShadowResolution());
    }

    public boolean isActive() {
        return Initializer.CONFIG.shaderQuality == ordinal();
    }

    public static ShaderQuality detectCurrent() {
        int current = Initializer.CONFIG.shaderQuality;
        for (ShaderQuality q : values()) {
            if (q.ordinal() == current) return q;
        }
        return MEDIUM;
    }
}
