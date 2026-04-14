package net.vulkanmod.mixin.render.feature;

import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(CustomFeatureRenderer.Storage.class)
public interface CustomFeatureStorageAccessor {
    @Accessor("customGeometrySubmits")
    Map<RenderType, List<SubmitNodeStorage.CustomGeometrySubmit>> vulkanmod$getCustomGeometrySubmits();
}
