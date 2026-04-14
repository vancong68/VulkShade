package net.vulkanmod.mixin.render.feature;

import net.minecraft.client.renderer.feature.ModelPartFeatureRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(ModelPartFeatureRenderer.Storage.class)
public interface ModelPartFeatureStorageAccessor {
    @Accessor("modelPartSubmits")
    Map<RenderType, List<SubmitNodeStorage.ModelPartSubmit>> vulkanmod$getModelPartSubmits();
}
