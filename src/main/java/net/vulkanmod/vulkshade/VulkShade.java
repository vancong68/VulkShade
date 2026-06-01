package net.vulkanmod.vulkshade;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.config.QualityPreset;
import net.vulkanmod.vulkshade.config.VulkShadeConfig;
import net.vulkanmod.vulkshade.effects.PostProcessingPipeline;
import net.vulkanmod.vulkshade.fallback.CompatibilityCheck;
import net.vulkanmod.vulkshade.fallback.RendererBackend;
import net.vulkanmod.vulkshade.optimization.*;
import net.vulkanmod.vulkshade.pipeline.PipelineCacheManager;
import net.vulkanmod.vulkshade.render.VoxyLODManager;
import net.vulkanmod.vulkshade.shader.ShaderManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class VulkShade {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade");
    private static VulkShade INSTANCE;
    private boolean initialized = false;

    private ShaderManager shaderManager;
    private VulkShadeConfig vulkshadeConfig;
    private RendererBackend rendererBackend;

    private ChunkBatchManager chunkBatchManager;
    private FrameSyncManager frameSyncManager;
    private DescriptorPoolManager descriptorPoolManager;
    private CullingSystemUpgrade cullingSystemUpgrade;
    private ShaderVariantSystem shaderVariantSystem;
    private AsyncShaderCompiler asyncShaderCompiler;
    private PerformanceScaler performanceScaler;
    private ChunkMeshStreamer chunkMeshStreamer;
    private VoxyLODManager voxyLODManager;
    private PostProcessingPipeline postProcessingPipeline;
    private boolean postProcessingReady;

    private VulkShade() {
    }

    public static VulkShade getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new VulkShade();
        }
        return INSTANCE;
    }

    public void initialize() {
        if (initialized) return;

        LOGGER.info("=== VulkShade Optimization System ===");

        this.vulkshadeConfig = VulkShadeConfig.getInstance();

        QualityPreset detected = QualityPreset.detectCurrent();
        if (detected != QualityPreset.CUSTOM) {
            vulkshadeConfig.applyPreset(detected);
            LOGGER.info("Detected quality preset: {}", detected.getDisplayName());
        } else {
            LOGGER.info("Using custom quality configuration");
        }

        this.rendererBackend = RendererBackend.getInstance();

        this.shaderManager = ShaderManager.getInstance();
        this.shaderManager.initialize(vulkshadeConfig.isShaderHotReload());

        this.shaderVariantSystem = ShaderVariantSystem.getInstance();
        this.shaderVariantSystem.initialize();

        vulkshadeConfig.loadFeatureOverridesFromPersistedConfig();

        this.asyncShaderCompiler = AsyncShaderCompiler.getInstance();

        this.cullingSystemUpgrade = CullingSystemUpgrade.getInstance();

        this.chunkBatchManager = ChunkBatchManager.getInstance();

        this.performanceScaler = PerformanceScaler.getInstance();
        this.performanceScaler.initialize();

        this.chunkMeshStreamer = ChunkMeshStreamer.getInstance();
        this.chunkMeshStreamer.initialize();

        this.voxyLODManager = VoxyLODManager.getInstance();
        this.voxyLODManager.initialize();

        LOGGER.info("Culling: occlusion={}, Hi-Z={}, LOD={}",
            vulkshadeConfig.isOcclusionCulling(),
            vulkshadeConfig.isHizCulling(),
            vulkshadeConfig.isLodCulling());
        LOGGER.info("Performance: adaptive={}, dynamicRD={}, framePacing={}",
            vulkshadeConfig.isAdaptivePerformance(),
            vulkshadeConfig.isDynamicRenderDistance(),
            vulkshadeConfig.isFramePacingEnabled());

        this.initialized = true;
        LOGGER.info("VulkShade Optimization System initialized");
    }

    public void onClientStarted() {
        if (!initialized) return;
        if (performanceScaler != null) performanceScaler.syncWithGameOptions();
    }

    public void onVulkanInitComplete() {
        if (!initialized) return;
        if (rendererBackend.isVulkanActive()) {
            PipelineCacheManager.initialize();
            this.descriptorPoolManager = DescriptorPoolManager.getInstance();
            this.frameSyncManager = new FrameSyncManager();
            this.frameSyncManager.initialize();
            if (cullingSystemUpgrade != null) {
                cullingSystemUpgrade.initialize();
            }
            PipelineCacheManager.saveToDisk();
            LOGGER.info("FrameSyncManager initialized with {} frames",
                frameSyncManager.getFrameCount());
        }
    }

    public void onBeginFrame() {
        if (!initialized) return;
        long frameStartTime = System.nanoTime();

        if (chunkBatchManager != null) chunkBatchManager.beginFrame();
        if (asyncShaderCompiler != null) asyncShaderCompiler.pollCompleted();
        if (chunkMeshStreamer != null) chunkMeshStreamer.beginFrame();
        if (performanceScaler != null) performanceScaler.beginFrame(frameStartTime);
        if (voxyLODManager != null) voxyLODManager.updateTiles();

        if (!postProcessingReady) {
            initPostProcessing();
        }
    }

    private void initPostProcessing() {
        Renderer renderer = Renderer.getInstance();
        if (renderer == null) return;

        var swapChain = renderer.getSwapChain();
        if (swapChain == null) return;

        int fbWidth = swapChain.getWidth();
        int fbHeight = swapChain.getHeight();
        if (fbWidth <= 0 || fbHeight <= 0) return;

        try {
            this.postProcessingPipeline = new PostProcessingPipeline();
            var sceneColor = swapChain.getColorAttachment();
            if (sceneColor != null) {
                this.postProcessingPipeline.initialize(fbWidth, fbHeight, sceneColor);
            }

            renderer.addOnResizeCallback(() -> {
                if (postProcessingPipeline != null && initialized) {
                    int w = renderer.getSwapChain().getWidth();
                    int h = renderer.getSwapChain().getHeight();
                    if (w > 0 && h > 0) {
                        var color = renderer.getSwapChain().getColorAttachment();
                        if (color != null) {
                            postProcessingPipeline.resize(w, h, color);
                        }
                    }
                }
            });

            Renderer.postProcessCallback = this::onPostProcess;
            this.postProcessingReady = true;
            LOGGER.info("Post-processing pipeline initialized ({}x{})", fbWidth, fbHeight);
        } catch (Exception e) {
            LOGGER.error("Failed to initialize post-processing pipeline, disabling effects", e);
            this.postProcessingPipeline = null;
            this.postProcessingReady = true;
        }
    }

    public void onRenderLOD() {
        if (!initialized) return;
        if (voxyLODManager != null) voxyLODManager.renderLODTiles();
    }

    public void onPostProcess() {
        if (!initialized) return;
        if (postProcessingPipeline != null) {
            Renderer renderer = Renderer.getInstance();
            if (renderer == null) return;
            var swapChain = renderer.getSwapChain();
            if (swapChain == null) return;
            var sceneColor = swapChain.getColorAttachment();
            if (sceneColor == null) {
                LOGGER.warn("[Frame] Swap chain color attachment is null, skipping post-processing");
                return;
            }
            var cmd = Renderer.getCommandBuffer();
            if (cmd == null) {
                LOGGER.warn("[Frame] Command buffer is null, skipping post-processing");
                return;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[Frame] Running post-processing pipeline on image {} ({}x{})",
                    sceneColor.getId(), sceneColor.width, sceneColor.height);
            }
            MainPass mainPass = renderer.getMainPass();
            VulkanImage depthBuffer = mainPass != null ? mainPass.getDepthAttachmentImage() : null;
            postProcessingPipeline.render(cmd, sceneColor, depthBuffer);
        }
    }

    public void onEndFrame() {
        if (!initialized) return;
        if (frameSyncManager != null) frameSyncManager.endFrame();
        if (chunkMeshStreamer != null) chunkMeshStreamer.endFrame();
    }

    public void onShutdown() {
        if (!initialized) return;
        LOGGER.info("Shutting down VulkShade optimization systems...");

        if (asyncShaderCompiler != null) asyncShaderCompiler.shutdown();
        if (voxyLODManager != null) voxyLODManager.cleanup();
        if (chunkMeshStreamer != null) chunkMeshStreamer.cleanup();
        if (rendererBackend.isVulkanActive()) {
            PipelineCacheManager.destroy();
        }
        if (frameSyncManager != null) frameSyncManager.cleanup();
        if (descriptorPoolManager != null) descriptorPoolManager.cleanup();
        if (cullingSystemUpgrade != null) cullingSystemUpgrade.cleanup();
        if (shaderManager != null) shaderManager.shutdown();
        rendererBackend.shutdown();

        initialized = false;
        LOGGER.info("VulkShade shut down complete");
    }

    public ShaderManager getShaderManager() { return shaderManager; }
    public VulkShadeConfig getVulkShadeConfig() { return vulkshadeConfig; }
    public RendererBackend getRendererBackend() { return rendererBackend; }
    public ChunkBatchManager getChunkBatchManager() { return chunkBatchManager; }
    public FrameSyncManager getFrameSyncManager() { return frameSyncManager; }
    public DescriptorPoolManager getDescriptorPoolManager() { return descriptorPoolManager; }
    public CullingSystemUpgrade getCullingSystemUpgrade() { return cullingSystemUpgrade; }
    public ShaderVariantSystem getShaderVariantSystem() { return shaderVariantSystem; }
    public AsyncShaderCompiler getAsyncShaderCompiler() { return asyncShaderCompiler; }
    public PerformanceScaler getPerformanceScaler() { return performanceScaler; }
    public ChunkMeshStreamer getChunkMeshStreamer() { return chunkMeshStreamer; }
    public VoxyLODManager getVoxyLODManager() { return voxyLODManager; }
    public boolean isInitialized() { return initialized; }
}
