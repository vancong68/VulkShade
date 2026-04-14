package net.vulkanmod.mixin.render.feature;

import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(ModelFeatureRenderer.Storage.class)
public interface ModelFeatureStorageAccessor {
    @Accessor("opaqueModelSubmits")
    Map<RenderType, List<SubmitNodeStorage.ModelSubmit<?>>> vulkanmod$getOpaqueModelSubmits();

    @Accessor("translucentModelSubmits")
    List<SubmitNodeStorage.TranslucentModelSubmit<?>> vulkanmod$getTranslucentModelSubmits();
}
