package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(RenderType.CompositeState.class)
public interface RenderSetupAccessor {
    @Accessor("outputState")
    RenderStateShard.OutputStateShard vulkanmod$getOutputState();

    @Accessor("textureState")
    RenderStateShard.EmptyTextureStateShard vulkanmod$getTextureState();

    @Accessor("states")
    List<RenderStateShard> vulkanmod$getStates();

    default RenderStateShard.OutputStateShard getOutputTarget() {
        return this.vulkanmod$getOutputState();
    }

    default RenderStateShard.TexturingStateShard getTextureTransform() {
        return this.vulkanmod$findState(RenderStateShard.TexturingStateShard.class);
    }

    default RenderStateShard.LayeringStateShard getLayeringTransform() {
        return this.vulkanmod$findState(RenderStateShard.LayeringStateShard.class);
    }

    default Map<String, Object> getTextureBindings() {
        ResourceLocation location = ((EmptyTextureStateShardAccessor) this.vulkanmod$getTextureState())
                .callCutoutTexture()
                .orElse(null);
        return location == null ? Map.of() : Map.of("Sampler0", location);
    }

    private <T extends RenderStateShard> T vulkanmod$findState(Class<T> stateType) {
        for (RenderStateShard state : this.vulkanmod$getStates()) {
            if (stateType.isInstance(state)) {
                return stateType.cast(state);
            }
        }

        return null;
    }
}
