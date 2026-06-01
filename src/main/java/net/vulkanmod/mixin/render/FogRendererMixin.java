package net.vulkanmod.mixin.render;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public class FogRendererMixin {

    @Inject(method = "setupFog", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F"))
    private void onSetupFog(Camera camera, int i, boolean bl, DeltaTracker deltaTracker, float f,
                            ClientLevel clientLevel, CallbackInfoReturnable<Vector4f> cir,
                            @Local FogData fogData, @Local Vector4f fogColor) {
        if (!Initializer.CONFIG.featureFog) {
            fogData.renderDistanceStart = 16384.0f;
            fogData.renderDistanceEnd = 32768.0f;
            fogData.environmentalStart = 16384.0f;
            fogData.environmentalEnd = 32768.0f;
            fogData.skyEnd = 32768.0f;
            fogData.cloudEnd = 32768.0f;
        } else if (Initializer.CONFIG.voxyLODEnabled) {
            fogData.renderDistanceEnd = 1024.0f;
            fogData.renderDistanceStart = 512.0f;
            fogData.environmentalEnd = 1024.0f;
            fogData.environmentalStart = 512.0f;
            fogData.skyEnd = 1024.0f;
            fogData.cloudEnd = 1024.0f;
        }
        VRenderSystem.fogData = fogData;
        VRenderSystem.setShaderFogColor(fogColor.x(), fogColor.y(), fogColor.z(), fogColor.w());
    }
}
