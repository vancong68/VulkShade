package net.vulkanmod.mixin.render.sky;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.sky.SkyRenderer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.profiling.GpuFrameProfiler;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererSkyM {

    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("VulkShade-Sky");
    @Unique private static boolean loggedOnce = false;

    @Shadow private @Nullable ClientLevel level;
    @Shadow @Final private LevelTargetBundle targets;
    @Shadow @Final private LevelRenderState levelRenderState;

    @Unique private SkyRenderer skyRenderer;
    @Unique private float capturedPartialTick;
    @Unique private final Matrix4f capturedProjection = new Matrix4f();

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void captureMatrices(GraphicsResourceAllocator graphicsResourceAllocator, DeltaTracker deltaTracker,
                                 boolean bl, Camera camera, Matrix4f modelView, Matrix4f projection, Matrix4f matrix4f,
                                 GpuBufferSlice gpuBufferSlice, Vector4f vector4f, boolean bl2, CallbackInfo ci) {
        this.capturedPartialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        this.capturedProjection.set(projection);
    }

    @Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
    private void replaceSkyPass(FrameGraphBuilder frameGraphBuilder, Camera camera, GpuBufferSlice gpuBufferSlice, CallbackInfo ci) {
        if (Initializer.CONFIG.skyQuality == 0) {
            return;
        }

        if (this.skyRenderer == null) {
            this.skyRenderer = new SkyRenderer();
            LOGGER.info("[VulkShade-Sky] SkyRenderer initialized, addSkyPass mixin is active");
        }

        if (!loggedOnce) {
            LOGGER.info("[VulkShade-Sky] addSkyPass intercepted, level={}, camera available={}", this.level != null, camera != null);
            loggedOnce = true;
        }

        FramePass framePass = frameGraphBuilder.addPass("custom_sky");
        this.targets.main = framePass.readsAndWrites(this.targets.main);

        final float partialTick = this.capturedPartialTick;

        framePass.executes(() -> {
            Profiler profiler = Profiler.getMainProfiler();
            profiler.push("CustomSky");
            long startTime = System.nanoTime();
            GpuFrameProfiler gpuProfiler = Renderer.getInstance().getGpuProfiler();
            if (gpuProfiler != null) {
                gpuProfiler.beginStage(GpuFrameProfiler.Stage.SKY);
            }

            this.skyRenderer.renderSky(this.level, partialTick, camera, this.levelRenderState.skyRenderState.timeOfDay, this.capturedProjection);

            if (gpuProfiler != null) {
                gpuProfiler.endStage(GpuFrameProfiler.Stage.SKY);
            }

            WorldRenderer worldRenderer = WorldRenderer.getInstance();
            if (worldRenderer != null) {
                worldRenderer.addSkyPassTime(System.nanoTime() - startTime);
            }

            profiler.pop();
        });

        ci.cancel();
    }
}
