package net.vulkanmod.mixin.render.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherM {

    @Redirect(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitShadow(Lcom/mojang/blaze3d/vertex/PoseStack;FLjava/util/List;)V"
            )
    )
    private void vulkanmod$disableVanillaBlobShadow(SubmitNodeCollector submitNodeCollector,
                                                    PoseStack poseStack,
                                                    float shadowRadius,
                                                    List<EntityRenderState.ShadowPiece> shadowPieces) {
    }
}
