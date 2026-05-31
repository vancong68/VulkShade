package net.vulkanmod.vulkshade.effects;

import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class WaterShader {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-Water");

    private WaterQuality quality = WaterQuality.MEDIUM;
    private boolean waveSimulation = true;
    private boolean reflections = true;
    private float waveAmplitude = 0.1f;
    private float waveFrequency = 1.0f;
    private float waveSpeed = 0.5f;

    private GraphicsPipeline waterPipeline;
    private GraphicsPipeline underwaterPostPipeline;

    public enum WaterQuality {
        LOW,
        MEDIUM,
        HIGH,
        ULTRA
    }

    public WaterShader() {
    }

    public void initialize() {
        createPipelines();
    }

    public void reloadPipelines() {
        if (waterPipeline != null) waterPipeline.scheduleCleanUp();
        if (underwaterPostPipeline != null) underwaterPostPipeline.scheduleCleanUp();
        createPipelines();
    }

    public void cleanup() {
        if (waterPipeline != null) {
            waterPipeline.scheduleCleanUp();
            waterPipeline = null;
        }
        if (underwaterPostPipeline != null) {
            underwaterPostPipeline.scheduleCleanUp();
            underwaterPostPipeline = null;
        }
    }

    public GraphicsPipeline getWaterPipeline() { return waterPipeline; }
    public GraphicsPipeline getUnderwaterPostPipeline() { return underwaterPostPipeline; }
    public WaterQuality getQuality() { return quality; }

    public void setQuality(WaterQuality quality) {
        this.quality = quality;
        reloadPipelines();
    }

    public boolean isWaveSimulation() { return waveSimulation; }
    public void setWaveSimulation(boolean enabled) { this.waveSimulation = enabled; }
    public boolean isReflections() { return reflections; }
    public void setReflections(boolean enabled) { this.reflections = enabled; }

    public UBO createWaterUBO(int binding) {
        AlignedStruct.Builder builder = new AlignedStruct.Builder();
        builder.addUniformInfo(Uniform.createUniformInfo("float", "waveAmplitude", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("float", "waveFrequency", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("float", "waveSpeed", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("float", "time", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("int", "waterQuality", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("int", "waveSimEnabled", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("int", "reflectionsEnabled", 1));
        var ubo = builder.buildUBO(binding, -1);
        ubo.setUseGlobalBuffer(false);
        return ubo;
    }

    private void createPipelines() {
        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.COMPRESSED_TERRAIN, "water");
        String path = ShaderLoadUtil.resolveShaderPath("basic");
        var config = ShaderLoadUtil.getJsonConfig(path, "water");
        if (config != null) {
            builder.parseBindings(config);
            ShaderLoadUtil.loadShaders(builder, config, "water", path);
            waterPipeline = builder.createGraphicsPipeline();
        } else {
            LOGGER.warn("Water shader config not found, creating default");
            waterPipeline = createDefaultWaterPipeline();
        }
    }

    private GraphicsPipeline createDefaultWaterPipeline() {
        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.COMPRESSED_TERRAIN);
        builder.setVertShaderSPIRV(net.vulkanmod.vulkshade.shader.FallbackShader.getInstance()
            .getOrCreateFallback(net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind.VERTEX_SHADER));
        builder.setFragShaderSPIRV(net.vulkanmod.vulkshade.shader.FallbackShader.getInstance()
            .getOrCreateFallback(net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind.FRAGMENT_SHADER));
        return builder.createGraphicsPipeline();
    }
}
