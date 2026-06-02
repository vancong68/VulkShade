package net.vulkanmod.render;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;

import java.util.function.Function;

public abstract class PipelineManager {
    public static VertexFormat terrainVertexFormat;

    public static void setTerrainVertexFormat(VertexFormat format) {
        terrainVertexFormat = format;
    }

    static GraphicsPipeline
            terrainShader, terrainShaderEarlyZ, shadowPipeline,
            fastBlitPipeline, cloudsPipeline, skyPipeline, underwaterPipeline, volumetricPipeline,
            fsrEasuPipeline, fsrRcasPipeline,
            tonemapPipeline;

    private static Function<TerrainRenderType, GraphicsPipeline> shaderGetter;

    public static void init() {
        setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        createBasicPipelines();
        setDefaultShader();
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
    }

    public static void setDefaultShader() {
        setShaderGetter(
                renderType -> renderType == TerrainRenderType.TRANSLUCENT ? terrainShaderEarlyZ : terrainShader);
    }

    private static void createBasicPipelines() {
        terrainShaderEarlyZ = createPipeline("terrain_earlyz", terrainVertexFormat);
        terrainShader = createPipeline("terrain", terrainVertexFormat);
        shadowPipeline = createPipeline("shadow", terrainVertexFormat);
        fastBlitPipeline = createPipeline("blit", CustomVertexFormat.NONE);
        fsrEasuPipeline = createPipeline("fsr_easu", CustomVertexFormat.NONE);
        fsrRcasPipeline = createPipeline("fsr_rcas", CustomVertexFormat.NONE);
        cloudsPipeline = createPipeline("clouds", DefaultVertexFormat.POSITION_COLOR);
        skyPipeline = createPipeline("sky", CustomVertexFormat.NONE);
        underwaterPipeline = createPipeline("underwater", CustomVertexFormat.NONE);
        volumetricPipeline = createPipeline("volumetric", CustomVertexFormat.NONE);
        tonemapPipeline = createPipeline("tonemap", CustomVertexFormat.NONE);
    }

    private static GraphicsPipeline createPipeline(String configName, VertexFormat vertexFormat) {
        Pipeline.Builder pipelineBuilder = new Pipeline.Builder(vertexFormat, configName);

        final String path = ShaderLoadUtil.resolveShaderPath("basic");
        JsonObject config = ShaderLoadUtil.getJsonConfig(path, configName);
        pipelineBuilder.parseBindings(config);

        ShaderLoadUtil.loadShaders(pipelineBuilder, config, configName, path);

        var pipeline = pipelineBuilder.createGraphicsPipeline();

        for (var buffer : pipeline.getBuffers()) {
            buffer.setUseGlobalBuffer(true);
        }

        return pipeline;
    }

    public static GraphicsPipeline getTerrainShader(TerrainRenderType renderType) {
        return shaderGetter.apply(renderType);
    }

    public static void setShaderGetter(Function<TerrainRenderType, GraphicsPipeline> consumer) {
        shaderGetter = consumer;
    }

    public static GraphicsPipeline getTerrainDirectShader(RenderType renderType) {
        return terrainShader;
    }

    public static GraphicsPipeline getTerrainIndirectShader(RenderType renderType) {
        return terrainShaderEarlyZ;
    }

    public static GraphicsPipeline getFastBlitPipeline() {
        return fastBlitPipeline;
    }

    public static GraphicsPipeline getShadowPipeline() {
        return shadowPipeline;
    }

    public static GraphicsPipeline getFsrEasuPipeline() {
        return fsrEasuPipeline;
    }

    public static GraphicsPipeline getFsrRcasPipeline() {
        return fsrRcasPipeline;
    }

    public static GraphicsPipeline getCloudsPipeline() {
        return cloudsPipeline;
    }

    public static GraphicsPipeline getSkyPipeline() {
        return skyPipeline;
    }

    public static GraphicsPipeline getUnderwaterPipeline() {
        return underwaterPipeline;
    }

    public static GraphicsPipeline getVolumetricPipeline() {
        return volumetricPipeline;
    }

    public static GraphicsPipeline getTonemapPipeline() {
        return tonemapPipeline;
    }

    public static void destroyPipelines() {
        terrainShaderEarlyZ.cleanUp();
        terrainShader.cleanUp();
        shadowPipeline.cleanUp();
        fastBlitPipeline.cleanUp();
        fsrEasuPipeline.cleanUp();
        fsrRcasPipeline.cleanUp();
        cloudsPipeline.cleanUp();
        skyPipeline.cleanUp();
        underwaterPipeline.cleanUp();
        volumetricPipeline.cleanUp();
        tonemapPipeline.cleanUp();
    }
}
