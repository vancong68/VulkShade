package net.vulkanmod.mixin.texture.update;

import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.Tickable;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.texture.SpriteUpdateUtil;
import net.vulkanmod.render.texture.emissive.EmissiveTextureManager;
import net.vulkanmod.render.texture.pbr.PBRTextureManager;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.Set;

@Mixin(TextureManager.class)
public abstract class MTextureManager {

    @Shadow @Final private Set<Tickable> tickableTextures;

    /**
     * @author
     */
    @Overwrite
    public void tick() {
        if (Renderer.skipRendering || !Initializer.CONFIG.textureAnimations)
            return;

        //Debug D
        for (Tickable tickable : this.tickableTextures) {
            tickable.tick();
        }

        EmissiveTextureManager.INSTANCE.tick();
        PBRTextureManager.INSTANCE.tick();
        SpriteUpdateUtil.transitionLayouts();
    }

    @Inject(method = "reload", at = @At("HEAD"))
    private void onReload(PreparableReloadListener.SharedState sharedState, Executor preparationExecutor,
                          PreparableReloadListener.PreparationBarrier barrier, Executor applyExecutor,
                          CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        EmissiveTextureManager.INSTANCE.markDirty();
        PBRTextureManager.INSTANCE.markDirty();
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void onClose(CallbackInfo ci) {
        EmissiveTextureManager.INSTANCE.close();
        PBRTextureManager.INSTANCE.close();
    }
}
