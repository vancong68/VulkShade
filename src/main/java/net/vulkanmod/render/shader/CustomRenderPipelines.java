package net.vulkanmod.render.shader;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public class CustomRenderPipelines {

    public static final List<RenderPipeline> pipelines = new ArrayList<>();
    private static final ResourceLocation SHADOW_ENTITY_SHADER = ResourceLocation.fromNamespaceAndPath("vulkanmod", "core/shadow_entity");

    public static final RenderPipeline.Snippet GUI_TRIANGLES_SNIPPET = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                                                                           .withVertexShader("core/gui")
                                                                           .withFragmentShader("core/gui")
                                                                           .withBlend(BlendFunction.TRANSLUCENT)
                                                                           .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
                                                                           .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                                                                           .buildSnippet();
    public static final RenderPipeline.Snippet SHADOW_ENTITY_SNIPPET = RenderPipeline.builder()
                                                                                      .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                                                                                      .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                                                                                      .withVertexShader(SHADOW_ENTITY_SHADER)
                                                                                      .withFragmentShader(SHADOW_ENTITY_SHADER)
                                                                                      .withSampler("Sampler0")
                                                                                      .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
                                                                                      .buildSnippet();

    public static final RenderPipeline GUI_TRIANGLES = register(RenderPipeline.builder(GUI_TRIANGLES_SNIPPET).withLocation("pipeline/gui").build());
    public static final RenderPipeline SHADOW_ENTITY = register(
            RenderPipeline.builder(SHADOW_ENTITY_SNIPPET)
                          .withLocation(ResourceLocation.fromNamespaceAndPath("vulkanmod", "pipeline/shadow_entity"))
                          .withCull(false)
                          .withColorWrite(false)
                          .build()
    );

    static RenderPipeline register(RenderPipeline pipeline) {
        pipelines.add(pipeline);
        return pipeline;
    }

    public static RenderPipeline getShadowEntityPipeline(RenderPipeline pipeline) {
        if (pipeline == RenderPipelines.ARMOR_CUTOUT_NO_CULL
            || pipeline == RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL
            || pipeline == RenderPipelines.ARMOR_TRANSLUCENT
            || pipeline == RenderPipelines.ENTITY_SOLID
            || pipeline == RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD
            || pipeline == RenderPipelines.ENTITY_CUTOUT
            || pipeline == RenderPipelines.ENTITY_CUTOUT_NO_CULL
            || pipeline == RenderPipelines.ENTITY_CUTOUT_NO_CULL_Z_OFFSET
            || pipeline == RenderPipelines.ENTITY_TRANSLUCENT
            || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE
            || pipeline == RenderPipelines.ENTITY_SMOOTH_CUTOUT
            || pipeline == RenderPipelines.ENTITY_DECAL
            || pipeline == RenderPipelines.ENTITY_NO_OUTLINE
            || pipeline == RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL) {
            return SHADOW_ENTITY;
        }

        return null;
    }
}
