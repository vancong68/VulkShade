package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

@Mixin(RenderStateShard.TextureStateShard.class)
public interface TextureBindingAccessor {
    @Accessor("texture")
    Optional<ResourceLocation> vulkanmod$getTexture();

    default ResourceLocation getLocation() {
        return this.vulkanmod$getTexture().orElse(null);
    }
}
