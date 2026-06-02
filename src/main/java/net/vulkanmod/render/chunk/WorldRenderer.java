package net.vulkanmod.render.chunk;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.Zone;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.task.TaskDispatcher;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import net.vulkanmod.render.profiling.BuildTimeProfiler;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.profiling.Profiler.Result;
import net.vulkanmod.render.sky.SkyRenderer;
import net.vulkanmod.render.texture.emissive.EmissiveTextureManager;

import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.profiling.GpuFrameProfiler;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

import static org.lwjgl.vulkan.VK10.*;

import java.util.*;

public class WorldRenderer {
    private static final ResourceLocation CLOUD_TEXTURE_LOCATION = ResourceLocation.fromNamespaceAndPath("vulkanmod", "textures/effects/makeup_clouds.png");
    private static final ResourceLocation WATER_NOISE_TEXTURE_LOCATION = ResourceLocation.fromNamespaceAndPath("vulkanmod", "textures/effects/makeup_water.png");
    private static final int SHADOW_MAP_RESOLUTION = 840;
    private static final long SHADOW_UPDATE_INTERVAL_NS = 8_333_333L;
    private static final double ENTITY_SHADOW_DISTANCE = 128.0;
    private static final double ENTITY_SHADOW_DISTANCE_SQR = ENTITY_SHADOW_DISTANCE * ENTITY_SHADOW_DISTANCE;
    private static final float ENTITY_SHADOW_CULL_RADIUS = 4.0f;
    private static final long PERF_LOG_INTERVAL_NS = 1_000_000_000L;
    private static final boolean PERF_LOGGING = isPerfLoggingEnabled();
    private static WorldRenderer INSTANCE;

    private Framebuffer shadowFramebuffer;
    private RenderPass shadowRenderPass;
    private VkGpuTexture shadowColorTexture;
    private GpuTextureView shadowColorTextureView;
    private VkGpuTexture shadowDepthTexture;
    private GpuTextureView shadowDepthTextureView;
    private Framebuffer waterSceneFramebuffer;
    private VkGpuTexture waterSceneColorTexture;
    private GpuTextureView waterSceneColorTextureView;
    private VkGpuTexture waterSceneDepthTexture;
    private GpuTextureView waterSceneDepthTextureView;
    private Framebuffer postSceneFramebuffer;
    private VkGpuTexture postSceneColorTexture;
    private GpuTextureView postSceneColorTextureView;
    private VkGpuTexture postSceneDepthTexture;
    private GpuTextureView postSceneDepthTextureView;
    private final RenderBuffers shadowRenderBuffers;
    private final FeatureRenderDispatcher shadowFeatureRenderDispatcher;
    private final PerspectiveProjectionMatrixBuffer shadowProjectionMatrixBuffer;
    private final VFrustum shadowFrustum = new VFrustum();
    private boolean entityShadowPassActive;

    public static WorldRenderer init(EntityRenderDispatcher entityRenderDispatcher,
                                     BlockEntityRenderDispatcher blockEntityRenderDispatcher,
                                     RenderBuffers renderBuffers,
                                     LevelRenderState levelRenderState,
                                     FeatureRenderDispatcher featureRenderDispatcher) {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        else {
            return INSTANCE = new WorldRenderer(entityRenderDispatcher, blockEntityRenderDispatcher, renderBuffers, levelRenderState, featureRenderDispatcher);
        }
    }

    private final Minecraft minecraft;
    private ClientLevel level;
    private int renderDistance;
    private final RenderBuffers renderBuffers;

    private final EntityRenderDispatcher entityRenderDispatcher;
    private final BlockEntityRenderDispatcher blockEntityRenderDispatcher;
    private final LevelRenderState levelRenderState;
    private final FeatureRenderDispatcher featureRenderDispatcher;

    private float partialTick;
    private Vec3 cameraPos;
    private Matrix4f currentFrameModelView = new Matrix4f();
    private Matrix4f currentFrameProjection = new Matrix4f();
    private int lastCameraSectionX;
    private int lastCameraSectionY;
    private int lastCameraSectionZ;
    private float lastCameraX;
    private float lastCameraY;
    private float lastCameraZ;
    private float lastCamRotX;
    private float lastCamRotY;

    private SectionGrid sectionGrid;

    private SectionGraph sectionGraph;
    private boolean graphNeedsUpdate;

    private final Set<BlockEntity> globalBlockEntities = Sets.newHashSet();

    private final TaskDispatcher taskDispatcher;

    private double xTransparentOld;
    private double yTransparentOld;
    private double zTransparentOld;
    private long frameId;
    private long lastShadowUpdateTimeNs = Long.MIN_VALUE;
    private boolean hasCachedShadowState;
    private boolean shadowPipelineUnsupported;
    private boolean shadowPipelineFailureLogged;
    private final float[] cachedShadowModelView = new float[16];
    private final float[] cachedShadowProjection = new float[16];
    private final float[] cachedShadowLightPosition = new float[3];
    private float cachedShadowDayNightMix;
    private long perfFrameCount;
    private long perfWindowStartNs = Long.MIN_VALUE;
    private long perfFrameStartNs = Long.MIN_VALUE;
    private long perfFrameTotalNs;
    private long perfSetupRendererNs;
    private long perfCameraRepositionNs;
    private long perfSectionGraphUpdateNs;
    private long perfUploadSectionsNs;
    private long perfSkyNs;
    private long perfShadowPassNs;
    private long perfEntityShadowPassNs;
    private long perfWaterCaptureNs;
    private long perfPostCaptureNs;
    private long perfVolumetricPostNs;
    private long perfWaterPostNs;
    private long perfOpaqueTerrainNs;
    private long perfTranslucentTerrainNs;
    private long perfBlockEntitiesNs;

    IndirectBuffer[] indirectBuffers;

    public RenderRegionBuilder renderRegionCache;

    private final List<Runnable> onAllChangedCallbacks = new ObjectArrayList<>();

    private WorldRenderer(EntityRenderDispatcher entityRenderDispatcher,
                          BlockEntityRenderDispatcher blockEntityRenderDispatcher,
                          RenderBuffers renderBuffers,
                          LevelRenderState levelRenderState,
                          FeatureRenderDispatcher featureRenderDispatcher)
    {
        this.minecraft = Minecraft.getInstance();
        this.renderBuffers = renderBuffers;
        this.entityRenderDispatcher = entityRenderDispatcher;
        this.blockEntityRenderDispatcher = blockEntityRenderDispatcher;
        this.levelRenderState = levelRenderState;
        this.featureRenderDispatcher = featureRenderDispatcher;
        this.shadowRenderBuffers = new RenderBuffers(RenderType.TRANSIENT_BUFFER_SIZE);
        this.shadowFeatureRenderDispatcher = new FeatureRenderDispatcher(
                new SubmitNodeStorage(),
                this.minecraft.getBlockRenderer(),
                this.shadowRenderBuffers.bufferSource(),
                this.minecraft.getAtlasManager(),
                this.shadowRenderBuffers.outlineBufferSource(),
                this.shadowRenderBuffers.crumblingBufferSource(),
                this.minecraft.font
        );
        this.shadowProjectionMatrixBuffer = new PerspectiveProjectionMatrixBuffer("entity_shadow_projection");

        this.renderRegionCache = new RenderRegionBuilder();
        this.taskDispatcher = new TaskDispatcher();

        ChunkTask.setTaskDispatcher(this.taskDispatcher);
        allocateIndirectBuffers();
        TerrainRenderType.updateMapping();
        initShadowPass();
        initWaterSceneCapture();
        initPostSceneCapture();

        Renderer.getInstance().addOnResizeCallback(() -> {
            if (this.indirectBuffers.length != Renderer.getFramesNum())
                allocateIndirectBuffers();
            initWaterSceneCapture();
            initPostSceneCapture();
        });
    }

    private void allocateIndirectBuffers() {
        if (this.indirectBuffers != null)
            Arrays.stream(this.indirectBuffers).forEach(Buffer::scheduleFree);

        this.indirectBuffers = new IndirectBuffer[Renderer.getFramesNum()];

        for (int i = 0; i < this.indirectBuffers.length; ++i) {
            this.indirectBuffers[i] = new IndirectBuffer(1000000, MemoryTypes.HOST_MEM);
        }
    }

    private void benchCallback() {
        BuildTimeProfiler.runBench(this.graphNeedsUpdate || !this.taskDispatcher.isIdle());
    }

    private static boolean isPerfLoggingEnabled() {
        String value = System.getenv("VULKANMOD_PERF_LOG");
        return value != null && !value.isBlank() && !"0".equals(value) && !"false".equalsIgnoreCase(value);
    }

    public void beginFrame() {
        if (!PERF_LOGGING) {
            return;
        }

        long now = System.nanoTime();
        if (this.perfWindowStartNs == Long.MIN_VALUE) {
            this.perfWindowStartNs = now;
        }
        if (this.perfFrameStartNs == Long.MIN_VALUE) {
            this.perfFrameStartNs = now;
        }
    }

    public void addSkyPassTime(long durationNs) {
        if (!PERF_LOGGING) {
            return;
        }

        this.perfSkyNs += durationNs;
    }

    public void setupRenderer(Camera camera, Frustum frustum, boolean isCapturedFrustum, boolean spectator) {
        long startTime = PERF_LOGGING ? System.nanoTime() : 0L;
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Setup_Renderer");

        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();

        benchCallback();
        this.frameId++;
        this.cameraPos = camera.getPosition();
        if (this.minecraft.options.getEffectiveRenderDistance() != this.renderDistance) {
            this.allChanged();
        }

        mcProfiler.push("camera");
        float cameraX = (float) cameraPos.x();
        float cameraY = (float) cameraPos.y();
        float cameraZ = (float) cameraPos.z();
        int sectionX = SectionPos.posToSectionCoord(cameraX);
        int sectionY = SectionPos.posToSectionCoord(cameraY);
        int sectionZ = SectionPos.posToSectionCoord(cameraZ);

        profiler.push("reposition");
        long repositionStartTime = PERF_LOGGING ? System.nanoTime() : 0L;
        if (this.lastCameraSectionX != sectionX || this.lastCameraSectionY != sectionY || this.lastCameraSectionZ != sectionZ) {
            this.lastCameraSectionX = sectionX;
            this.lastCameraSectionY = sectionY;
            this.lastCameraSectionZ = sectionZ;
            this.sectionGrid.repositionCamera(cameraX, cameraZ);
        }
        profiler.pop();
        if (PERF_LOGGING) {
            this.perfCameraRepositionNs += System.nanoTime() - repositionStartTime;
        }

        double entityDistanceScaling = this.minecraft.options.entityDistanceScaling().get();
        Entity.setViewScale(Mth.clamp((double) this.renderDistance / 8.0D, 1.0D, 2.5D) * entityDistanceScaling);

        mcProfiler.popPush("cull");

        mcProfiler.popPush("update");

        boolean cameraMoved = false;
        float d_xRot = Math.abs(camera.getXRot() - this.lastCamRotX);
        float d_yRot = Math.abs(camera.getYRot() - this.lastCamRotY);
        cameraMoved |= d_xRot > 2.0f || d_yRot > 2.0f;

        cameraMoved |= cameraX != this.lastCameraX || cameraY != this.lastCameraY || cameraZ != this.lastCameraZ;
        this.graphNeedsUpdate |= cameraMoved;

        if (!isCapturedFrustum) {
            //Debug
//            this.graphNeedsUpdate = true;

            if (this.graphNeedsUpdate()) {
                long sectionGraphUpdateStartTime = PERF_LOGGING ? System.nanoTime() : 0L;
                this.graphNeedsUpdate = false;
                this.lastCameraX = cameraX;
                this.lastCameraY = cameraY;
                this.lastCameraZ = cameraZ;
                this.lastCamRotX = camera.getXRot();
                this.lastCamRotY = camera.getYRot();

                this.sectionGraph.update(camera, frustum, spectator);
                if (PERF_LOGGING) {
                    this.perfSectionGraphUpdateNs += System.nanoTime() - sectionGraphUpdateStartTime;
                }
            }
        }

        this.indirectBuffers[Renderer.getCurrentFrame()].reset();

        mcProfiler.pop();
        profiler.pop();

        if (PERF_LOGGING) {
            this.perfSetupRendererNs += System.nanoTime() - startTime;
        }
    }

    public void uploadSections() {
        long startTime = PERF_LOGGING ? System.nanoTime() : 0L;
        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        mcProfiler.push("upload");

        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Uploads");

        try {
            if (this.taskDispatcher.updateSections())
                this.graphNeedsUpdate = true;
        } catch (Exception e) {
            Initializer.LOGGER.error(e.getMessage());
            allChanged();
        }

        profiler.pop();

        mcProfiler.pop();

        if (PERF_LOGGING) {
            this.perfUploadSectionsNs += System.nanoTime() - startTime;
        }
    }

    public boolean isSectionCompiled(BlockPos blockPos) {
        RenderSection renderSection = this.sectionGrid.getSectionAtBlockPos(blockPos);
        return renderSection != null && renderSection.isCompiled();
    }

    public void allChanged() {
        if (this.level != null) {
            this.level.clearTintCaches();

            this.renderRegionCache.clear();
            this.taskDispatcher.createThreads(Initializer.CONFIG.builderThreads);

            this.graphNeedsUpdate = true;

            this.renderDistance = this.minecraft.options.getEffectiveRenderDistance();
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
            }

            this.taskDispatcher.clearBatchQueue();
            synchronized (this.globalBlockEntities) {
                this.globalBlockEntities.clear();
            }

            this.sectionGrid = new SectionGrid(this.level, this.renderDistance);
            this.sectionGraph = new SectionGraph(this.level, this.sectionGrid, this.taskDispatcher);

            this.onAllChangedCallbacks.forEach(Runnable::run);

            Entity entity = this.minecraft.getCameraEntity();
            if (entity != null) {
                this.sectionGrid.repositionCamera(entity.getX(), entity.getZ());
            }

        }
    }

    public void setLevel(@Nullable ClientLevel level) {
        this.lastCameraX = Float.MIN_VALUE;
        this.lastCameraY = Float.MIN_VALUE;
        this.lastCameraZ = Float.MIN_VALUE;
        this.lastCameraSectionX = Integer.MIN_VALUE;
        this.lastCameraSectionY = Integer.MIN_VALUE;
        this.lastCameraSectionZ = Integer.MIN_VALUE;

//        this.entityRenderDispatcher.setLevel(level);
        this.level = level;
        ChunkStatusMap.createInstance(renderDistance);
        if (level != null) {
            this.allChanged();
        } else {
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
                this.sectionGrid = null;
            }

            this.taskDispatcher.stopThreads();

            this.graphNeedsUpdate = true;
        }

    }

    public void addOnAllChangedCallback(Runnable runnable) {
        this.onAllChangedCallbacks.add(runnable);
    }

    public void clearOnAllChangedCallbacks() {
        this.onAllChangedCallbacks.clear();
    }

    private void initShadowPass() {
        // Shadow framebuffer needs a dummy color attachment because VulkanMod's
        // framebuffer/render-pass code assumes a color attachment exists.
        shadowFramebuffer = new Framebuffer(
            new Framebuffer.Builder("ShadowMap", SHADOW_MAP_RESOLUTION, SHADOW_MAP_RESOLUTION, 1, true)
        );

        RenderPass.Builder rpBuilder = RenderPass.builder(shadowFramebuffer);
        rpBuilder.getColorAttachmentInfo()
            .setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        rpBuilder.getDepthAttachmentInfo()
            .setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE)
            .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        shadowRenderPass = rpBuilder.build();

        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
        shadowColorTexture = device.gpuTextureFromVulkanImage(shadowFramebuffer.getColorAttachment());
        shadowColorTextureView = device.createTextureView(shadowColorTexture);
        shadowDepthTexture = device.gpuTextureFromVulkanImage(shadowFramebuffer.getDepthAttachment());
        shadowDepthTextureView = device.createTextureView(shadowDepthTexture);
    }

    private void initWaterSceneCapture() {
        cleanupWaterSceneCapture();

        var mainPass = Renderer.getInstance().getMainPass();
        int renderWidth = mainPass.getSceneRenderWidth();
        int renderHeight = mainPass.getSceneRenderHeight();
        if (renderWidth <= 0 || renderHeight <= 0) {
            return;
        }

        VulkanImage sourceColor = mainPass.getColorAttachmentImage();
        VulkanImage sourceDepth = mainPass.getDepthAttachmentImage();
        VulkanImage colorAttachment = VulkanImage.builder(renderWidth, renderHeight)
                                                 .setName("WaterSceneCapture Color")
                                                 .setFormat(sourceColor.format)
                                                 .setUsage(
                                                         VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                                         VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                                         VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                                         VK_IMAGE_USAGE_SAMPLED_BIT
                                                 )
                                                 .setLinearFiltering(true)
                                                 .setClamp(true)
                                                 .createVulkanImage();
        VulkanImage depthAttachment = VulkanImage.builder(renderWidth, renderHeight)
                                                 .setName("WaterSceneCapture Depth")
                                                 .setFormat(sourceDepth.format)
                                                 .setUsage(
                                                         VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                                         VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                                         VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT |
                                                         VK_IMAGE_USAGE_SAMPLED_BIT
                                                 )
                                                 .setLinearFiltering(false)
                                                 .setClamp(true)
                                                 .createVulkanImage();

        this.waterSceneFramebuffer = Framebuffer.builder(colorAttachment, depthAttachment).build();

        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
        this.waterSceneColorTexture = device.gpuTextureFromVulkanImage(colorAttachment);
        this.waterSceneColorTextureView = device.createTextureView(this.waterSceneColorTexture);
        this.waterSceneDepthTexture = device.gpuTextureFromVulkanImage(depthAttachment);
        this.waterSceneDepthTextureView = device.createTextureView(this.waterSceneDepthTexture);
    }

    private void cleanupWaterSceneCapture() {
        if (this.waterSceneColorTextureView != null) {
            this.waterSceneColorTextureView.close();
            this.waterSceneColorTextureView = null;
        }
        if (this.waterSceneColorTexture != null) {
            this.waterSceneColorTexture.close();
            this.waterSceneColorTexture = null;
        }
        if (this.waterSceneDepthTextureView != null) {
            this.waterSceneDepthTextureView.close();
            this.waterSceneDepthTextureView = null;
        }
        if (this.waterSceneDepthTexture != null) {
            this.waterSceneDepthTexture.close();
            this.waterSceneDepthTexture = null;
        }
        if (this.waterSceneFramebuffer != null) {
            this.waterSceneFramebuffer.cleanUp();
            this.waterSceneFramebuffer = null;
        }
    }

    private void initPostSceneCapture() {
        cleanupPostSceneCapture();

        var mainPass = Renderer.getInstance().getMainPass();
        int renderWidth = mainPass.getSceneRenderWidth();
        int renderHeight = mainPass.getSceneRenderHeight();
        if (renderWidth <= 0 || renderHeight <= 0) {
            return;
        }

        int captureWidth = getPostSceneCaptureWidth(renderWidth);
        int captureHeight = getPostSceneCaptureHeight(renderHeight);
        VulkanImage sourceColor = mainPass.getColorAttachmentImage();
        VulkanImage sourceDepth = mainPass.getDepthAttachmentImage();

        VulkanImage colorAttachment = VulkanImage.builder(captureWidth, captureHeight)
                                                 .setName("VolumetricSceneCapture Color")
                                                 .setFormat(sourceColor.format)
                                                 .setUsage(
                                                         VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                                         VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                                         VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                                         VK_IMAGE_USAGE_SAMPLED_BIT
                                                 )
                                                 .setLinearFiltering(true)
                                                 .setClamp(true)
                                                 .createVulkanImage();
        VulkanImage depthAttachment = VulkanImage.builder(captureWidth, captureHeight)
                                                 .setName("VolumetricSceneCapture Depth")
                                                 .setFormat(sourceDepth.format)
                                                 .setUsage(
                                                         VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                                         VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                                         VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT |
                                                         VK_IMAGE_USAGE_SAMPLED_BIT
                                                 )
                                                 .setLinearFiltering(false)
                                                 .setClamp(true)
                                                 .createVulkanImage();

        this.postSceneFramebuffer = Framebuffer.builder(colorAttachment, depthAttachment).build();

        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
        this.postSceneColorTexture = device.gpuTextureFromVulkanImage(colorAttachment);
        this.postSceneColorTextureView = device.createTextureView(this.postSceneColorTexture);
        this.postSceneDepthTexture = device.gpuTextureFromVulkanImage(depthAttachment);
        this.postSceneDepthTextureView = device.createTextureView(this.postSceneDepthTexture);
    }

    private void cleanupPostSceneCapture() {
        if (this.postSceneColorTextureView != null) {
            this.postSceneColorTextureView.close();
            this.postSceneColorTextureView = null;
        }
        if (this.postSceneColorTexture != null) {
            this.postSceneColorTexture.close();
            this.postSceneColorTexture = null;
        }
        if (this.postSceneDepthTextureView != null) {
            this.postSceneDepthTextureView.close();
            this.postSceneDepthTextureView = null;
        }
        if (this.postSceneDepthTexture != null) {
            this.postSceneDepthTexture.close();
            this.postSceneDepthTexture = null;
        }
        if (this.postSceneFramebuffer != null) {
            this.postSceneFramebuffer.cleanUp();
            this.postSceneFramebuffer = null;
        }
    }

    private void captureWaterSceneTextures() {
        long startTime = PERF_LOGGING ? System.nanoTime() : 0L;
        if (this.waterSceneFramebuffer == null) {
            initWaterSceneCapture();
        }

        if (this.waterSceneFramebuffer == null) {
            return;
        }

        var mainPass = Renderer.getInstance().getMainPass();
        int renderWidth = mainPass.getSceneRenderWidth();
        int renderHeight = mainPass.getSceneRenderHeight();
        if (this.waterSceneFramebuffer.getWidth() != renderWidth || this.waterSceneFramebuffer.getHeight() != renderHeight) {
            initWaterSceneCapture();
        }

        if (this.waterSceneFramebuffer == null) {
            return;
        }

        GpuFrameProfiler gpuProfiler = Renderer.getInstance().getGpuProfiler();
        if (gpuProfiler != null) {
            gpuProfiler.beginStage(GpuFrameProfiler.Stage.WATER_SCENE_COPY);
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);

        VulkanImage srcColor = mainPass.getColorAttachmentImage();
        VulkanImage srcDepth = mainPass.getDepthAttachmentImage();
        VulkanImage dstColor = this.waterSceneFramebuffer.getColorAttachment();
        VulkanImage dstDepth = this.waterSceneFramebuffer.getDepthAttachment();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            srcColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dstColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyImage(stack, commandBuffer, srcColor, dstColor, VK_IMAGE_ASPECT_COLOR_BIT);
            dstColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            srcColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            srcDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dstDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyImage(stack, commandBuffer, srcDepth, dstDepth, VK_IMAGE_ASPECT_DEPTH_BIT);
            dstDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            srcDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }

        Renderer.getInstance().getMainPass().rebindMainTarget();

        if (gpuProfiler != null) {
            gpuProfiler.endStage(GpuFrameProfiler.Stage.WATER_SCENE_COPY);
        }

        if (PERF_LOGGING) {
            this.perfWaterCaptureNs += System.nanoTime() - startTime;
        }
    }

    private void capturePostSceneTextures() {
        long startTime = PERF_LOGGING ? System.nanoTime() : 0L;
        if (this.postSceneFramebuffer == null) {
            initPostSceneCapture();
        }

        if (this.postSceneFramebuffer == null) {
            return;
        }

        var mainPass = Renderer.getInstance().getMainPass();
        int expectedWidth = getPostSceneCaptureWidth(mainPass.getSceneRenderWidth());
        int expectedHeight = getPostSceneCaptureHeight(mainPass.getSceneRenderHeight());
        if (this.postSceneFramebuffer.getWidth() != expectedWidth || this.postSceneFramebuffer.getHeight() != expectedHeight) {
            initPostSceneCapture();
        }

        if (this.postSceneFramebuffer == null) {
            return;
        }

        GpuFrameProfiler gpuProfiler = Renderer.getInstance().getGpuProfiler();
        if (gpuProfiler != null) {
            gpuProfiler.beginStage(GpuFrameProfiler.Stage.POST_SCENE_COPY);
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);

        VulkanImage srcColor = mainPass.getColorAttachmentImage();
        VulkanImage srcDepth = mainPass.getDepthAttachmentImage();
        VulkanImage dstColor = this.postSceneFramebuffer.getColorAttachment();
        VulkanImage dstDepth = this.postSceneFramebuffer.getDepthAttachment();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            srcColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dstColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyOrBlitImage(stack, commandBuffer, srcColor, dstColor, VK_IMAGE_ASPECT_COLOR_BIT, VK_FILTER_LINEAR);
            dstColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            srcColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            srcDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dstDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyOrBlitImage(stack, commandBuffer, srcDepth, dstDepth, VK_IMAGE_ASPECT_DEPTH_BIT, VK_FILTER_NEAREST);
            dstDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            srcDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }

        Renderer.getInstance().getMainPass().rebindMainTarget();

        if (gpuProfiler != null) {
            gpuProfiler.endStage(GpuFrameProfiler.Stage.POST_SCENE_COPY);
        }

        if (PERF_LOGGING) {
            this.perfPostCaptureNs += System.nanoTime() - startTime;
        }
    }

    private static void copyImage(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage srcImage, VulkanImage dstImage, int aspectMask) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource()
              .aspectMask(aspectMask)
              .mipLevel(0)
              .baseArrayLayer(0)
              .layerCount(1);
        region.dstSubresource()
              .aspectMask(aspectMask)
              .mipLevel(0)
              .baseArrayLayer(0)
              .layerCount(1);
        region.extent().set(Math.min(srcImage.width, dstImage.width), Math.min(srcImage.height, dstImage.height), 1);

        vkCmdCopyImage(
                commandBuffer,
                srcImage.getId(),
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dstImage.getId(),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                region
        );
    }

    private static void copyOrBlitImage(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage srcImage, VulkanImage dstImage, int aspectMask, int filter) {
        if (srcImage.width == dstImage.width && srcImage.height == dstImage.height) {
            copyImage(stack, commandBuffer, srcImage, dstImage, aspectMask);
            return;
        }

        org.lwjgl.vulkan.VkImageBlit.Buffer region = org.lwjgl.vulkan.VkImageBlit.calloc(1, stack);
        region.srcOffsets(0).set(0, 0, 0);
        region.srcOffsets(1).set(srcImage.width, srcImage.height, 1);
        region.srcSubresource()
              .aspectMask(aspectMask)
              .mipLevel(0)
              .baseArrayLayer(0)
              .layerCount(1);

        region.dstOffsets(0).set(0, 0, 0);
        region.dstOffsets(1).set(dstImage.width, dstImage.height, 1);
        region.dstSubresource()
              .aspectMask(aspectMask)
              .mipLevel(0)
              .baseArrayLayer(0)
              .layerCount(1);

        VK11.vkCmdBlitImage(
                commandBuffer,
                srcImage.getId(),
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dstImage.getId(),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                region,
                filter
        );
    }

    private static int getPostSceneCaptureWidth(int swapChainWidth) {
        int scale = Math.max(1, Initializer.CONFIG.volumetricResolutionScale);
        return Math.max(1, (swapChainWidth + scale - 1) / scale);
    }

    private static int getPostSceneCaptureHeight(int swapChainHeight) {
        int scale = Math.max(1, Initializer.CONFIG.volumetricResolutionScale);
        return Math.max(1, (swapChainHeight + scale - 1) / scale);
    }

    private void updateEyeInWaterState() {
        Camera camera = this.minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            VRenderSystem.eyeInWater = 0;
            return;
        }

        FogType fogType = camera.getFluidInCamera();
        VRenderSystem.eyeInWater = switch (fogType) {
            case WATER -> 1;
            case LAVA -> 2;
            case POWDER_SNOW -> 3;
            default -> 0;
        };
    }

    public void renderShadowPass(double camX, double camY, double camZ) {
        if (this.sectionGrid == null || this.sectionGraph == null || this.level == null) return;
        if (this.shadowPipelineUnsupported) return;

        long startTime = PERF_LOGGING ? System.nanoTime() : 0L;
        if (!shouldRefreshShadowMap()) {
            restoreCachedShadowState();
            if (PERF_LOGGING) {
                this.perfShadowPassNs += System.nanoTime() - startTime;
            }
            return;
        }

        // Update sky state to compute shadow matrices
        SkyRenderer.updateSkyState(this.level, this.partialTick, this.minecraft.gameRenderer.getMainCamera(), this.currentFrameProjection);

        // Build the shadow MVP from the shadow matrices
        Matrix4f shadowMV = new Matrix4f(VRenderSystem.shadowModelView.buffer.asFloatBuffer());
        Matrix4f shadowProj = new Matrix4f(VRenderSystem.shadowProjection.buffer.asFloatBuffer());
        Matrix4f shadowMVP = new Matrix4f();
        shadowProj.mul(shadowMV, shadowMVP);
        this.shadowFrustum.setCamOffset(camX, camY, camZ);
        this.shadowFrustum.calculateFrustum(new Matrix4f(shadowMV), new Matrix4f(shadowProj));

        // Set the shadow MVP as the current MVP for the shadow pipeline
        VRenderSystem.applyMVP(shadowMV, shadowProj);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);

        VRenderSystem.disableCull();
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.depthFunc(GL11.GL_LEQUAL);
        VRenderSystem.disableBlend();
        // Keep writes enabled for the dummy shadow color attachment. Some
        // drivers are stricter about fully masked color targets during
        // pipeline creation, while this attachment is never sampled anyway.
        VRenderSystem.colorMask(true, true, true, true);

        Renderer renderer = Renderer.getInstance();
        GpuFrameProfiler gpuProfiler = renderer.getGpuProfiler();
        boolean gpuStageActive = false;
        try {
            if (gpuProfiler != null) {
                gpuProfiler.beginStage(GpuFrameProfiler.Stage.SHADOW);
                gpuStageActive = true;
            }
            renderer.beginRenderPass(shadowRenderPass, shadowFramebuffer);

            GraphicsPipeline pipeline = PipelineManager.getShadowPipeline();
            renderer.bindGraphicsPipeline(pipeline);

            // Bind block atlas texture for alpha testing
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            AbstractTexture blockAtlasTexture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
            blockAtlasTexture.setUseMipmaps(true);
            RenderSystem.setShaderTexture(0, blockAtlasTexture.getTextureView());
            VTextureSelector.bindShaderTextures(pipeline);

            IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
            Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);

            // Render opaque terrain layers to shadow map
            Set<TerrainRenderType> allowedRenderTypes = Initializer.CONFIG.uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
            for (TerrainRenderType shadowRenderType : new TerrainRenderType[]{
                    TerrainRenderType.SOLID, TerrainRenderType.CUTOUT, TerrainRenderType.CUTOUT_MIPPED}) {
                if (!allowedRenderTypes.contains(shadowRenderType)) continue;

                shadowRenderType.setCutoutUniform();
                renderer.uploadAndBindUBOs(pipeline);
                ChunkAreaManager chunkAreaManager = this.sectionGrid.getChunkAreaManager();
                for (int areaIndex = 0; areaIndex < chunkAreaManager.size; ++areaIndex) {
                    ChunkArea chunkArea = chunkAreaManager.getChunkArea(areaIndex);
                    if (chunkArea == null) {
                        continue;
                    }
                    if (!isChunkAreaVisibleInShadowFrustum(chunkArea)) {
                        continue;
                    }

                    DrawBuffers drawBuffers = chunkArea.drawBuffers;

                    if (drawBuffers.getAreaBuffer(shadowRenderType) != null) {
                        drawBuffers.bindBuffers(Renderer.getCommandBuffer(), pipeline, shadowRenderType, camX, camY, camZ);
                        // Draw all casters in areas that intersect the shadow camera
                        // volume, instead of brute-forcing every loaded chunk area.
                        drawBuffers.buildDrawBatchesAllDirect(shadowRenderType);
                    }
                }
            }

            VRenderSystem.setModelOffset(0, 0, 0);
            renderer.pushConstants(pipeline);

            renderer.endRenderPass();
            renderEntityShadowCasters(shadowMV, shadowProj);

            cacheShadowState();
            this.lastShadowUpdateTimeNs = System.nanoTime();
        } catch (RuntimeException e) {
            if (renderer.getBoundRenderPass() == shadowRenderPass) {
                renderer.endRenderPass();
            }

            this.shadowPipelineUnsupported = true;
            this.hasCachedShadowState = false;
            this.lastShadowUpdateTimeNs = Long.MIN_VALUE;

            if (!this.shadowPipelineFailureLogged) {
                Initializer.LOGGER.warn("Disabling custom shadow pass after pipeline initialization failure; continuing without custom terrain shadows and volumetric lighting.", e);
                this.shadowPipelineFailureLogged = true;
            }
        } finally {
            VRenderSystem.colorMask(true, true, true, true);

            if (gpuStageActive && gpuProfiler != null) {
                gpuProfiler.endStage(GpuFrameProfiler.Stage.SHADOW);
            }

            if (PERF_LOGGING) {
                this.perfShadowPassNs += System.nanoTime() - startTime;
            }
        }
    }

    private boolean shouldRefreshShadowMap() {
        return !this.hasCachedShadowState || (System.nanoTime() - this.lastShadowUpdateTimeNs) >= SHADOW_UPDATE_INTERVAL_NS;
    }

    private void cacheShadowState() {
        for (int i = 0; i < 16; ++i) {
            int offset = i * Float.BYTES;
            this.cachedShadowModelView[i] = VRenderSystem.shadowModelView.getFloat(offset);
            this.cachedShadowProjection[i] = VRenderSystem.shadowProjection.getFloat(offset);
        }

        for (int i = 0; i < 3; ++i) {
            this.cachedShadowLightPosition[i] = VRenderSystem.shadowLightPosition.getFloat(i * Float.BYTES);
        }

        this.cachedShadowDayNightMix = VRenderSystem.shadowDayNightMix;
        this.hasCachedShadowState = true;
    }

    private void restoreCachedShadowState() {
        if (!this.hasCachedShadowState) {
            return;
        }

        for (int i = 0; i < 16; ++i) {
            int offset = i * Float.BYTES;
            VRenderSystem.shadowModelView.putFloat(offset, this.cachedShadowModelView[i]);
            VRenderSystem.shadowProjection.putFloat(offset, this.cachedShadowProjection[i]);
        }

        for (int i = 0; i < 3; ++i) {
            VRenderSystem.shadowLightPosition.putFloat(i * Float.BYTES, this.cachedShadowLightPosition[i]);
        }

        VRenderSystem.shadowDayNightMix = this.cachedShadowDayNightMix;
    }

    private boolean isChunkAreaVisibleInShadowFrustum(ChunkArea chunkArea) {
        if (chunkArea.sectionsContained <= 0) {
            return false;
        }

        Vector3i position = chunkArea.getPosition();
        float minX = position.x();
        float minY = position.y();
        float minZ = position.z();
        float maxX = minX + (ChunkAreaManager.WIDTH << 4);
        float maxY = minY + (ChunkAreaManager.HEIGHT << 4);
        float maxZ = minZ + (ChunkAreaManager.WIDTH << 4);

        return this.shadowFrustum.testFrustum(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void renderEntityShadowCasters(Matrix4f shadowModelView, Matrix4f shadowProjection) {
        CameraRenderState cameraRenderState = this.levelRenderState.cameraRenderState;
        if (cameraRenderState == null || this.levelRenderState.entityRenderStates.isEmpty()) {
            return;
        }

        long startTime = PERF_LOGGING ? System.nanoTime() : 0L;

        SubmitNodeStorage submitNodeStorage = this.shadowFeatureRenderDispatcher.getSubmitNodeStorage();
        submitNodeStorage.clear();

        ShadowSubmitCollector collector = new ShadowSubmitCollector(submitNodeStorage);
        PoseStack poseStack = new PoseStack();
        Vec3 cameraPosition = cameraRenderState.pos;

        for (EntityRenderState entityRenderState : this.levelRenderState.entityRenderStates) {
            if (entityRenderState.isInvisible || !shouldSubmitEntityShadow(entityRenderState, cameraPosition)) {
                continue;
            }

            this.entityRenderDispatcher.submit(
                    entityRenderState,
                    cameraRenderState,
                    entityRenderState.x - cameraPosition.x(),
                    entityRenderState.y - cameraPosition.y(),
                    entityRenderState.z - cameraPosition.z(),
                    poseStack,
                    collector
            );
        }

        LocalPlayer localPlayer = this.minecraft.player;
        if (localPlayer != null && this.minecraft.options.getCameraType().isFirstPerson() && !localPlayer.isSpectator()) {
            EntityRenderState localPlayerRenderState = this.entityRenderDispatcher.extractEntity(localPlayer, this.partialTick);
            if (!localPlayerRenderState.isInvisible && shouldSubmitEntityShadow(localPlayerRenderState, cameraPosition)) {
                this.entityRenderDispatcher.submit(
                        localPlayerRenderState,
                        cameraRenderState,
                        localPlayerRenderState.x - cameraPosition.x(),
                        localPlayerRenderState.y - cameraPosition.y(),
                        localPlayerRenderState.z - cameraPosition.z(),
                        poseStack,
                        collector
                );
            }
        }

        if (submitNodeStorage.getSubmitsPerOrder().isEmpty()) {
            submitNodeStorage.clear();
            return;
        }

        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();
        modelViewStack.pushMatrix();
        modelViewStack.set(shadowModelView);
        RenderSystem.setProjectionMatrix(
                this.shadowProjectionMatrixBuffer.getBuffer(new Matrix4f(shadowProjection)),
                com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC
        );

        this.entityShadowPassActive = true;
        GpuFrameProfiler gpuProfiler = Renderer.getInstance().getGpuProfiler();
        if (gpuProfiler != null) {
            gpuProfiler.beginStage(GpuFrameProfiler.Stage.SHADOW_ENTITY);
        }
        try {
            this.shadowFeatureRenderDispatcher.renderAllFeatures();
            this.shadowRenderBuffers.bufferSource().endBatch();
            this.shadowRenderBuffers.crumblingBufferSource().endBatch();
            this.shadowRenderBuffers.outlineBufferSource().endOutlineBatch();
        } finally {
            if (gpuProfiler != null) {
                gpuProfiler.endStage(GpuFrameProfiler.Stage.SHADOW_ENTITY);
            }
            this.entityShadowPassActive = false;
            modelViewStack.popMatrix();
            RenderSystem.restoreProjectionMatrix();
        }

        if (PERF_LOGGING) {
            this.perfEntityShadowPassNs += System.nanoTime() - startTime;
        }
    }

    private boolean shouldSubmitEntityShadow(EntityRenderState entityRenderState, Vec3 cameraPosition) {
        double dx = entityRenderState.x - cameraPosition.x();
        double dy = entityRenderState.y - cameraPosition.y();
        double dz = entityRenderState.z - cameraPosition.z();
        if ((dx * dx) + (dy * dy) + (dz * dz) > ENTITY_SHADOW_DISTANCE_SQR) {
            return false;
        }

        double minX = entityRenderState.x - ENTITY_SHADOW_CULL_RADIUS;
        double minY = entityRenderState.y - ENTITY_SHADOW_CULL_RADIUS;
        double minZ = entityRenderState.z - ENTITY_SHADOW_CULL_RADIUS;
        double maxX = entityRenderState.x + ENTITY_SHADOW_CULL_RADIUS;
        double maxY = entityRenderState.y + ENTITY_SHADOW_CULL_RADIUS;
        double maxZ = entityRenderState.z + ENTITY_SHADOW_CULL_RADIUS;
        return this.shadowFrustum.testFrustum((float) minX, (float) minY, (float) minZ, (float) maxX, (float) maxY, (float) maxZ);
    }

    public void bindShadowMap() {
        // Bind shadow depth texture for sampling in terrain shaders
        if (shadowFramebuffer != null && this.shadowDepthTextureView != null) {
            VTextureSelector.bindTexture(5, shadowFramebuffer.getDepthAttachment());
            RenderSystem.setShaderTexture(5, this.shadowDepthTextureView);
        }
    }

    public void renderSectionLayer(TerrainRenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
        long startTime = PERF_LOGGING ? System.nanoTime() : 0L;
        Renderer.getInstance().getMainPass().rebindMainTarget();

        this.sortTranslucentSections(camX, camY, camZ);

        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        Zone zone = mcProfiler.zone(() -> "render_" + renderType);

        final boolean isTranslucent = renderType == TerrainRenderType.TRANSLUCENT;
        final boolean indirectDraw = Initializer.CONFIG.indirectDraw;

        if (!isTranslucent) {
            GlStateManager._disableBlend();
        } else {
            GlStateManager._enableBlend();
            VRenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }

        VRenderSystem.enableCull();
        VRenderSystem.depthFunc(GL11.GL_LEQUAL);

        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);

        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disablePolygonOffset();
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);

        VRenderSystem.applyMVP(modelView, projection);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);

        Renderer renderer = Renderer.getInstance();
        GraphicsPipeline pipeline = PipelineManager.getTerrainShader(renderType);
        renderer.bindGraphicsPipeline(pipeline);

        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        AbstractTexture blockAtlasTexture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
        blockAtlasTexture.setUseMipmaps(true);

        RenderSystem.setShaderTexture(0, blockAtlasTexture.getTextureView());
        RenderSystem.setShaderTexture(2, Minecraft.getInstance().gameRenderer.lightTexture().getTextureView());
        RenderSystem.setShaderTexture(6, EmissiveTextureManager.INSTANCE.getEmissiveTextureView(TextureAtlas.LOCATION_BLOCKS));
        // Bind shadow map for terrain lighting
        bindShadowMap();

        if (isTranslucent) {
            updateEyeInWaterState();
            if (Initializer.CONFIG.waterQuality == 0) {
                captureWaterSceneTextures();
            }
            updateSkyStatePreservingCachedShadow();

            AbstractTexture waterNoiseTexture = textureManager.getTexture(WATER_NOISE_TEXTURE_LOCATION);
            waterNoiseTexture.setUseMipmaps(false);
            waterNoiseTexture.setFilter(true, false);
            waterNoiseTexture.setClamp(false);
            RenderSystem.setShaderTexture(3, waterNoiseTexture.getTextureView());

            AbstractTexture cloudTexture = textureManager.getTexture(CLOUD_TEXTURE_LOCATION);
            cloudTexture.setUseMipmaps(false);
            cloudTexture.setFilter(true, false);
            cloudTexture.setClamp(false);
            RenderSystem.setShaderTexture(4, cloudTexture.getTextureView());

            if (Initializer.CONFIG.waterQuality == 0 && this.waterSceneColorTextureView != null) {
                RenderSystem.setShaderTexture(6, this.waterSceneColorTextureView);
            }
            if (Initializer.CONFIG.waterQuality == 0 && this.waterSceneDepthTextureView != null) {
                RenderSystem.setShaderTexture(7, this.waterSceneDepthTextureView);
            }
        }

        GpuFrameProfiler gpuProfiler = renderer.getGpuProfiler();
        if (gpuProfiler != null) {
            gpuProfiler.beginStage(isTranslucent ? GpuFrameProfiler.Stage.TRANSLUCENT_TERRAIN : GpuFrameProfiler.Stage.OPAQUE_TERRAIN);
        }
        VTextureSelector.bindShaderTextures(pipeline);

        IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);

        int currentFrame = Renderer.getCurrentFrame();
        Set<TerrainRenderType> allowedRenderTypes = Initializer.CONFIG.uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
        if (allowedRenderTypes.contains(renderType)) {
            renderType.setCutoutUniform();
            renderer.uploadAndBindUBOs(pipeline);

            for (Iterator<ChunkArea> iterator = this.sectionGraph.getChunkAreaQueue().iterator(isTranslucent); iterator.hasNext(); ) {
                ChunkArea chunkArea = iterator.next();
                var queue = chunkArea.sectionQueue;
                DrawBuffers drawBuffers = chunkArea.drawBuffers;

                if (drawBuffers.getAreaBuffer(renderType) != null && queue.size() > 0) {

                    drawBuffers.bindBuffers(Renderer.getCommandBuffer(), pipeline, renderType, camX, camY, camZ);

                    if (indirectDraw)
                        drawBuffers.buildDrawBatchesIndirect(cameraPos, indirectBuffers[currentFrame], queue, renderType);
                    else
                        drawBuffers.buildDrawBatchesDirect(cameraPos, queue, renderType);
                }
            }
        }

        if (renderType == TerrainRenderType.CUTOUT || renderType == TerrainRenderType.TRIPWIRE) {
            indirectBuffers[currentFrame].submitUploads();
//            uniformBuffers.submitUploads();
        }

        // Need to reset push constants in case the pipeline will still be used for rendering
        if (!indirectDraw) {
            VRenderSystem.setModelOffset(0, 0, 0);
            renderer.pushConstants(pipeline);
        }

        if (gpuProfiler != null) {
            gpuProfiler.endStage(isTranslucent ? GpuFrameProfiler.Stage.TRANSLUCENT_TERRAIN : GpuFrameProfiler.Stage.OPAQUE_TERRAIN);
        }

        zone.close();

        if (PERF_LOGGING) {
            if (isTranslucent) {
                this.perfTranslucentTerrainNs += System.nanoTime() - startTime;
            } else {
                this.perfOpaqueTerrainNs += System.nanoTime() - startTime;
            }
        }
    }

    public void renderWaterPost() {
        long startTime = PERF_LOGGING ? System.nanoTime() : 0L;
        if (Initializer.CONFIG.waterQuality != 0) {
            return;
        }
        updateEyeInWaterState();
        if (VRenderSystem.eyeInWater == 0 || this.level == null) {
            return;
        }

        captureWaterSceneTextures();

        if (this.waterSceneColorTextureView == null || this.waterSceneDepthTextureView == null) {
            return;
        }

        updateSkyStatePreservingCachedShadow();
        applyFrameMatrices();

        Renderer renderer = Renderer.getInstance();
        GpuFrameProfiler gpuProfiler = renderer.getGpuProfiler();
        if (gpuProfiler != null) {
            gpuProfiler.beginStage(GpuFrameProfiler.Stage.UNDERWATER_POST);
        }
        renderer.getMainPass().rebindMainTarget();

        GraphicsPipeline pipeline = PipelineManager.getUnderwaterPipeline();
        renderer.bindGraphicsPipeline(pipeline);

        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.disableCull();
        VRenderSystem.disableBlend();
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);

        RenderSystem.setShaderTexture(0, this.waterSceneColorTextureView);
        RenderSystem.setShaderTexture(1, this.waterSceneDepthTextureView);
        VTextureSelector.bindShaderTextures(pipeline);
        renderer.uploadAndBindUBOs(pipeline);

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        VK11.vkCmdDraw(commandBuffer, 3, 1, 0, 0);

        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.enableCull();

        if (gpuProfiler != null) {
            gpuProfiler.endStage(GpuFrameProfiler.Stage.UNDERWATER_POST);
        }

        if (PERF_LOGGING) {
            this.perfWaterPostNs += System.nanoTime() - startTime;
        }
    }

    public void renderVolumetricLightPost() {
        long startTime = PERF_LOGGING ? System.nanoTime() : 0L;
        if (!Initializer.CONFIG.volumetricLighting) {
            return;
        }
        if (this.shadowPipelineUnsupported) {
            return;
        }
        if (this.level == null || this.shadowDepthTextureView == null) {
            return;
        }
        if (!this.level.dimensionType().hasSkyLight()) {
            return;
        }

        updateEyeInWaterState();
        capturePostSceneTextures();
        if (this.postSceneColorTextureView == null || this.postSceneDepthTextureView == null) {
            return;
        }

        updateSkyStatePreservingCachedShadow();
        applyFrameMatrices();

        Renderer renderer = Renderer.getInstance();
        GpuFrameProfiler gpuProfiler = renderer.getGpuProfiler();
        if (gpuProfiler != null) {
            gpuProfiler.beginStage(GpuFrameProfiler.Stage.VOLUMETRIC_POST);
        }
        renderer.getMainPass().rebindMainTarget();

        GraphicsPipeline pipeline = PipelineManager.getVolumetricPipeline();
        renderer.bindGraphicsPipeline(pipeline);

        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.disableCull();
        VRenderSystem.disableBlend();
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);

        RenderSystem.setShaderTexture(0, this.postSceneColorTextureView);
        RenderSystem.setShaderTexture(1, this.postSceneDepthTextureView);
        RenderSystem.setShaderTexture(5, this.shadowDepthTextureView);
        VTextureSelector.bindShaderTextures(pipeline);
        renderer.uploadAndBindUBOs(pipeline);

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        VK11.vkCmdDraw(commandBuffer, 3, 1, 0, 0);

        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.enableCull();

        if (gpuProfiler != null) {
            gpuProfiler.endStage(GpuFrameProfiler.Stage.VOLUMETRIC_POST);
        }

        if (PERF_LOGGING) {
            this.perfVolumetricPostNs += System.nanoTime() - startTime;
        }
    }

    public void resolveMainPassForGui() {
        Renderer renderer = Renderer.getInstance();
        if (renderer == null) {
            return;
        }

        renderer.getMainPass().resolveForGui(Renderer.getCommandBuffer());
    }

    private void updateSkyStatePreservingCachedShadow() {
        SkyRenderer.updateSkyState(this.level, this.partialTick, this.minecraft.gameRenderer.getMainCamera(), this.currentFrameProjection);
        restoreCachedShadowState();
    }

    public void endFrame() {
        if (!PERF_LOGGING) {
            return;
        }

        long now = System.nanoTime();
        if (this.perfFrameStartNs != Long.MIN_VALUE) {
            this.perfFrameTotalNs += now - this.perfFrameStartNs;
            this.perfFrameStartNs = Long.MIN_VALUE;
        }

        this.perfFrameCount++;
        if (this.perfWindowStartNs == Long.MIN_VALUE) {
            this.perfWindowStartNs = now;
        }
        if ((now - this.perfWindowStartNs) < PERF_LOG_INTERVAL_NS) {
            return;
        }

        double frameScale = 1.0 / (double) this.perfFrameCount;
        double wallFrameMs = nanosToMs(now - this.perfWindowStartNs, frameScale);
        double totalMs = nanosToMs(this.perfFrameTotalNs, frameScale);
        double setupMs = nanosToMs(this.perfSetupRendererNs, frameScale);
        double repositionMs = nanosToMs(this.perfCameraRepositionNs, frameScale);
        double sectionGraphUpdateMs = nanosToMs(this.perfSectionGraphUpdateNs, frameScale);
        double uploadMs = nanosToMs(this.perfUploadSectionsNs, frameScale);
        double skyMs = nanosToMs(this.perfSkyNs, frameScale);
        double shadowMs = nanosToMs(this.perfShadowPassNs, frameScale);
        double entityShadowMs = nanosToMs(this.perfEntityShadowPassNs, frameScale);
        double waterCopyMs = nanosToMs(this.perfWaterCaptureNs, frameScale);
        double postCopyMs = nanosToMs(this.perfPostCaptureNs, frameScale);
        double volumetricMs = nanosToMs(this.perfVolumetricPostNs, frameScale);
        double underwaterMs = nanosToMs(this.perfWaterPostNs, frameScale);
        double opaqueMs = nanosToMs(this.perfOpaqueTerrainNs, frameScale);
        double translucentMs = nanosToMs(this.perfTranslucentTerrainNs, frameScale);
        double blockEntityMs = nanosToMs(this.perfBlockEntitiesNs, frameScale);
        double otherMs = Math.max(0.0, totalMs - (setupMs + uploadMs + skyMs + shadowMs + volumetricMs + underwaterMs + opaqueMs + translucentMs + blockEntityMs));

        List<PerfCategory> categories = new ArrayList<>(10);
        addPerfCategory(categories, "opaqueTerrain", opaqueMs);
        addPerfCategory(categories, "shadowPass", shadowMs);
        addPerfCategory(categories, "translucentTerrain", translucentMs);
        addPerfCategory(categories, "setupRenderer", setupMs);
        addPerfCategory(categories, "uploadSections", uploadMs);
        addPerfCategory(categories, "skyPass", skyMs);
        addPerfCategory(categories, "volumetricPost", volumetricMs);
        addPerfCategory(categories, "underwaterPost", underwaterMs);
        addPerfCategory(categories, "blockEntities", blockEntityMs);
        addPerfCategory(categories, "other", otherMs);
        categories.sort(Comparator.comparingDouble(PerfCategory::ms).reversed());

        StringBuilder topStages = new StringBuilder();
        for (int i = 0; i < categories.size(); ++i) {
            if (i > 0) {
                topStages.append(", ");
            }

            PerfCategory category = categories.get(i);
            topStages.append(category.name())
                     .append('=')
                     .append(String.format(Locale.ROOT, "%.3fms", category.ms()));
        }

        double fps = wallFrameMs > 0.0 ? 1000.0 / wallFrameMs : 0.0;
        Initializer.LOGGER.info(String.format(
                Locale.ROOT,
                "Perf avg over %d frames (~%.1f FPS wall, wallFrame=%.3fms, measuredFrame=%.3fms): %s",
                this.perfFrameCount,
                fps,
                wallFrameMs,
                totalMs,
                topStages
        ));
        Initializer.LOGGER.info(String.format(
                Locale.ROOT,
                "Perf breakdown: shadowEntity=%.3fms, waterSceneCopy=%.3fms, postSceneCopy=%.3fms",
                entityShadowMs,
                waterCopyMs,
                postCopyMs
        ));
        Initializer.LOGGER.info(String.format(
                Locale.ROOT,
                "Perf setup breakdown: reposition=%.3fms, sectionGraphUpdate=%.3fms",
                repositionMs,
                sectionGraphUpdateMs
        ));
        logGpuBreakdown();
        logProfilerBreakdown();

        this.perfWindowStartNs = now;
        this.perfFrameCount = 0;
        this.perfFrameTotalNs = 0L;
        this.perfSetupRendererNs = 0L;
        this.perfCameraRepositionNs = 0L;
        this.perfSectionGraphUpdateNs = 0L;
        this.perfUploadSectionsNs = 0L;
        this.perfSkyNs = 0L;
        this.perfShadowPassNs = 0L;
        this.perfEntityShadowPassNs = 0L;
        this.perfWaterCaptureNs = 0L;
        this.perfPostCaptureNs = 0L;
        this.perfOpaqueTerrainNs = 0L;
        this.perfTranslucentTerrainNs = 0L;
        this.perfVolumetricPostNs = 0L;
        this.perfWaterPostNs = 0L;
        this.perfBlockEntitiesNs = 0L;
    }

    private static double nanosToMs(long nanos, double frameScale) {
        return nanos * frameScale / 1_000_000.0;
    }

    private static void addPerfCategory(List<PerfCategory> categories, String name, double ms) {
        if (ms <= 0.001) {
            return;
        }

        categories.add(new PerfCategory(name, ms));
    }

    private static void logProfilerBreakdown() {
        var results = Profiler.getMainProfiler().getProfilerResults().getPartialResults();
        if (results.isEmpty()) {
            return;
        }

        List<Result> sortedResults = new ArrayList<>(results);
        sortedResults.sort(Comparator.comparingDouble((Result result) -> result.value).reversed());

        StringBuilder builder = new StringBuilder();
        int emitted = 0;
        for (Result result : sortedResults) {
            if (result.value <= 0.001f) {
                continue;
            }

            if (emitted > 0) {
                builder.append(", ");
            }

            builder.append(result.name)
                   .append('=')
                   .append(String.format(Locale.ROOT, "%.3fms", result.value));

            emitted++;
            if (emitted >= 10) {
                break;
            }
        }

        if (emitted > 0) {
            Initializer.LOGGER.info("Profiler top nodes: " + builder);
        }
    }

    private static void logGpuBreakdown() {
        Renderer renderer = Renderer.getInstance();
        if (renderer == null || renderer.getGpuProfiler() == null) {
            return;
        }

        GpuFrameProfiler.Snapshot snapshot = renderer.getGpuProfiler().snapshotAndReset();
        if (snapshot == null) {
            return;
        }

        double frameMs = snapshot.averageMs(GpuFrameProfiler.Stage.FRAME);
        double shadowMs = snapshot.averageMs(GpuFrameProfiler.Stage.SHADOW);
        double opaqueMs = snapshot.averageMs(GpuFrameProfiler.Stage.OPAQUE_TERRAIN);
        double translucentMs = snapshot.averageMs(GpuFrameProfiler.Stage.TRANSLUCENT_TERRAIN);
        double skyMs = snapshot.averageMs(GpuFrameProfiler.Stage.SKY);
        double waterCopyMs = snapshot.averageMs(GpuFrameProfiler.Stage.WATER_SCENE_COPY);
        double postCopyMs = snapshot.averageMs(GpuFrameProfiler.Stage.POST_SCENE_COPY);
        double fsrEasuMs = snapshot.averageMs(GpuFrameProfiler.Stage.FSR_EASU);
        double fsrRcasMs = snapshot.averageMs(GpuFrameProfiler.Stage.FSR_RCAS);
        double volumetricMs = snapshot.averageMs(GpuFrameProfiler.Stage.VOLUMETRIC_POST);
        double underwaterMs = snapshot.averageMs(GpuFrameProfiler.Stage.UNDERWATER_POST);
        double shadowEntityMs = snapshot.averageMs(GpuFrameProfiler.Stage.SHADOW_ENTITY);
        double otherMs = Math.max(0.0, frameMs - (shadowMs + opaqueMs + translucentMs + skyMs + waterCopyMs + postCopyMs + fsrEasuMs + fsrRcasMs + volumetricMs + underwaterMs));

        List<PerfCategory> categories = new ArrayList<>(11);
        addPerfCategory(categories, "other", otherMs);
        addPerfCategory(categories, "opaqueTerrain", opaqueMs);
        addPerfCategory(categories, "shadowPass", shadowMs);
        addPerfCategory(categories, "translucentTerrain", translucentMs);
        addPerfCategory(categories, "waterSceneCopy", waterCopyMs);
        addPerfCategory(categories, "fsrEasu", fsrEasuMs);
        addPerfCategory(categories, "fsrRcas", fsrRcasMs);
        addPerfCategory(categories, "volumetricPost", volumetricMs);
        addPerfCategory(categories, "skyPass", skyMs);
        addPerfCategory(categories, "underwaterPost", underwaterMs);
        addPerfCategory(categories, "postSceneCopy", postCopyMs);
        categories.sort(Comparator.comparingDouble(PerfCategory::ms).reversed());

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < categories.size(); ++i) {
            if (i > 0) {
                builder.append(", ");
            }

            PerfCategory category = categories.get(i);
            builder.append(category.name())
                   .append('=')
                   .append(String.format(Locale.ROOT, "%.3fms", category.ms()));
        }

        Initializer.LOGGER.info(String.format(
                Locale.ROOT,
                "GPU avg over %d frames (frame=%.3fms): %s",
                snapshot.frameCount(),
                frameMs,
                builder
        ));
        Initializer.LOGGER.info(String.format(
                Locale.ROOT,
                "GPU breakdown: shadowEntity=%.3fms",
                shadowEntityMs
        ));
    }

    private record PerfCategory(String name, double ms) {
    }

    private void sortTranslucentSections(double camX, double camY, double camZ) {
        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        mcProfiler.push("translucent_sort");
        double d0 = camX - this.xTransparentOld;
        double d1 = camY - this.yTransparentOld;
        double d2 = camZ - this.zTransparentOld;
        if (d0 * d0 + d1 * d1 + d2 * d2 > 2.0D) {
            this.xTransparentOld = camX;
            this.yTransparentOld = camY;
            this.zTransparentOld = camZ;
            int j = 0;

            Iterator<RenderSection> iterator = this.sectionGraph.getSectionQueue().iterator(false);

            while (iterator.hasNext() && j < 200) {
                RenderSection section = iterator.next();
                section.resortTransparency(this.taskDispatcher);

                if (!section.isCompletelyEmpty()) {
                    ++j;
                }
            }
        }

        mcProfiler.pop();
    }

    public void renderBlockEntities(PoseStack poseStack, LevelRenderState levelRenderState,
                                    SubmitNodeStorage submitNodeStorage,
                                    Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress) {
        long startTime = PERF_LOGGING ? System.nanoTime() : 0L;
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
        profiler.push("Block-entities");

        Vec3 vec3 = levelRenderState.cameraRenderState.pos;
        double camX = vec3.x();
        double camY = vec3.y();
        double camZ = vec3.z();

        for (RenderSection renderSection : this.sectionGraph.getBlockEntitiesSections()) {
            List<BlockEntity> list = renderSection.getCompiledSection().getBlockEntities();
            if (!list.isEmpty()) {
                for (BlockEntity blockEntity : list) {
                    BlockPos blockPos = blockEntity.getBlockPos();
                    SortedSet<BlockDestructionProgress> sortedSet = destructionProgress.get(blockPos.asLong());
                    ModelFeatureRenderer.CrumblingOverlay crumblingOverlay;
                    if (sortedSet != null && !sortedSet.isEmpty()) {
                        poseStack.pushPose();
                        poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
                        crumblingOverlay = new ModelFeatureRenderer.CrumblingOverlay(sortedSet.last()
                                                                                              .getProgress(), poseStack.last());
                        poseStack.popPose();
                    } else {
                        crumblingOverlay = null;
                    }

                    BlockEntityRenderState blockEntityRenderState = this.blockEntityRenderDispatcher.tryExtractRenderState(blockEntity, this.partialTick, crumblingOverlay);
                    if (blockEntityRenderState != null) {
                        levelRenderState.blockEntityRenderStates.add(blockEntityRenderState);
                    }
                }
            }
        }

        Iterator<BlockEntity> iterator = this.level.getGloballyRenderedBlockEntities().iterator();

        while (iterator.hasNext()) {
            BlockEntity blockEntity2 = iterator.next();
            if (blockEntity2.isRemoved()) {
                iterator.remove();
            } else {
                BlockEntityRenderState blockEntityRenderState2 = this.blockEntityRenderDispatcher.tryExtractRenderState(blockEntity2, this.partialTick, null);
                if (blockEntityRenderState2 != null) {
                    levelRenderState.blockEntityRenderStates.add(blockEntityRenderState2);
                }
            }
        }

        for (BlockEntityRenderState blockEntityRenderState : levelRenderState.blockEntityRenderStates) {
            BlockPos blockPos = blockEntityRenderState.blockPos;
            poseStack.pushPose();
            poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
            var blockEntityRenderDispatcher = this.minecraft.getBlockEntityRenderDispatcher();
            blockEntityRenderDispatcher.submit(blockEntityRenderState, poseStack, submitNodeStorage, levelRenderState.cameraRenderState);
            poseStack.popPose();
        }

        if (PERF_LOGGING) {
            this.perfBlockEntitiesNs += System.nanoTime() - startTime;
        }
    }

    public void setPartialTick(float partialTick) {
        this.partialTick = partialTick;
    }

    public void setFrameMatrices(Matrix4f modelView, Matrix4f projection) {
        this.currentFrameModelView.set(modelView);
        this.currentFrameProjection.set(projection);
    }

    public void scheduleGraphUpdate() {
        this.graphNeedsUpdate = true;
    }

    public boolean graphNeedsUpdate() {
        return this.graphNeedsUpdate;
    }

    public int getVisibleSectionsCount() {
        return this.sectionGraph.getSectionQueue().size();
    }

    public void setSectionDirty(int x, int y, int z, boolean flag) {
        this.sectionGrid.setDirty(x, y, z, flag);

        this.renderRegionCache.remove(x, z);
    }

    public SectionGrid getSectionGrid() {
        return this.sectionGrid;
    }

    public ChunkAreaManager getChunkAreaManager() {
        if (this.sectionGrid == null)
            return null;
        return this.sectionGrid.chunkAreaManager;
    }

    public TaskDispatcher getTaskDispatcher() {
        return taskDispatcher;
    }

    public short getLastFrame() {
        return this.sectionGraph.getLastFrame();
    }

    public int getRenderDistance() {
        return this.renderDistance;
    }

    public String getChunkStatistics() {
        if (this.sectionGraph == null) {
            return null;
        }

        return this.sectionGraph.getStatistics();
    }

    public @Nullable GpuTextureView getShadowDepthTextureView() {
        return this.shadowDepthTextureView;
    }

    public @Nullable GpuTextureView getShadowColorTextureView() {
        return this.shadowColorTextureView;
    }

    public boolean isEntityShadowPassActive() {
        return this.entityShadowPassActive;
    }

    private void applyFrameMatrices() {
        VRenderSystem.applyModelViewMatrix(this.currentFrameModelView);
        VRenderSystem.applyProjectionMatrix(this.currentFrameProjection);
        VRenderSystem.calculateMVP();
    }

    public void cleanUp() {
        if (indirectBuffers != null)
            Arrays.stream(indirectBuffers).forEach(Buffer::scheduleFree);
        this.shadowFeatureRenderDispatcher.close();
        this.shadowProjectionMatrixBuffer.close();
        if (shadowColorTextureView != null)
            shadowColorTextureView.close();
        if (shadowColorTexture != null)
            shadowColorTexture.close();
        if (shadowDepthTextureView != null)
            shadowDepthTextureView.close();
        if (shadowDepthTexture != null)
            shadowDepthTexture.close();
        if (shadowFramebuffer != null)
            shadowFramebuffer.cleanUp();
        cleanupWaterSceneCapture();
        cleanupPostSceneCapture();
    }

    public static WorldRenderer getInstance() {
        return INSTANCE;
    }

    public static ClientLevel getLevel() {
        return INSTANCE.level;
    }

    public static Vec3 getCameraPos() {
        return INSTANCE.cameraPos;
    }

    private static final class ShadowSubmitCollector implements net.minecraft.client.renderer.SubmitNodeCollector {
        private final net.minecraft.client.renderer.SubmitNodeCollector rootCollector;
        private final net.minecraft.client.renderer.OrderedSubmitNodeCollector orderedCollector;

        private ShadowSubmitCollector(net.minecraft.client.renderer.SubmitNodeCollector rootCollector) {
            this(rootCollector, rootCollector);
        }

        private ShadowSubmitCollector(net.minecraft.client.renderer.SubmitNodeCollector rootCollector,
                                      net.minecraft.client.renderer.OrderedSubmitNodeCollector orderedCollector) {
            this.rootCollector = rootCollector;
            this.orderedCollector = orderedCollector;
        }

        @Override
        public net.minecraft.client.renderer.OrderedSubmitNodeCollector order(int order) {
            return new ShadowSubmitCollector(this.rootCollector, this.rootCollector.order(order));
        }

        @Override
        public void submitHitbox(PoseStack poseStack, EntityRenderState entityRenderState, net.minecraft.client.renderer.entity.state.HitboxesRenderState hitboxesRenderState) {
        }

        @Override
        public void submitShadow(PoseStack poseStack, float shadowRadius, List<EntityRenderState.ShadowPiece> shadowPieces) {
        }

        @Override
        public void submitNameTag(PoseStack poseStack, Vec3 vec3, int i, net.minecraft.network.chat.Component component, boolean bl, int j, double d, CameraRenderState cameraRenderState) {
        }

        @Override
        public void submitText(PoseStack poseStack, float x, float y, net.minecraft.util.FormattedCharSequence formattedCharSequence, boolean bl, net.minecraft.client.gui.Font.DisplayMode displayMode, int i, int j, int k, int l) {
        }

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState entityRenderState, org.joml.Quaternionf quaternionf) {
        }

        @Override
        public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        }

        @Override
        public <S> void submitModel(net.minecraft.client.model.Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light, int overlay, int color, net.minecraft.client.renderer.texture.TextureAtlasSprite textureAtlasSprite, int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            if (net.vulkanmod.render.shader.CustomRenderPipelines.getShadowEntityPipeline(renderType.pipeline()) != null) {
                this.orderedCollector.submitModel(model, state, poseStack, renderType, light, overlay, color, textureAtlasSprite, outlineColor, crumblingOverlay);
            }
        }

        @Override
        public void submitModelPart(net.minecraft.client.model.geom.ModelPart modelPart, PoseStack poseStack, RenderType renderType, int light, int overlay, net.minecraft.client.renderer.texture.TextureAtlasSprite textureAtlasSprite, boolean bl, boolean bl2, int i, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, int j) {
            if (net.vulkanmod.render.shader.CustomRenderPipelines.getShadowEntityPipeline(renderType.pipeline()) != null) {
                this.orderedCollector.submitModelPart(modelPart, poseStack, renderType, light, overlay, textureAtlasSprite, bl, bl2, i, crumblingOverlay, j);
            }
        }

        @Override
        public void submitBlock(PoseStack poseStack, net.minecraft.world.level.block.state.BlockState blockState, int i, int j, int k) {
        }

        @Override
        public void submitMovingBlock(PoseStack poseStack, net.minecraft.client.renderer.block.MovingBlockRenderState movingBlockRenderState) {
        }

        @Override
        public void submitBlockModel(PoseStack poseStack, RenderType renderType, net.minecraft.client.renderer.block.model.BlockStateModel blockStateModel, float red, float green, float blue, int light, int overlay, int seed) {
            if (net.vulkanmod.render.shader.CustomRenderPipelines.getShadowEntityPipeline(renderType.pipeline()) != null) {
                this.orderedCollector.submitBlockModel(poseStack, renderType, blockStateModel, red, green, blue, light, overlay, seed);
            }
        }

        @Override
        public void submitItem(PoseStack poseStack, net.minecraft.world.item.ItemDisplayContext itemDisplayContext, int light, int overlay, int seed, int[] tints, List<net.minecraft.client.renderer.block.model.BakedQuad> quads, RenderType renderType, net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foilType) {
            if (net.vulkanmod.render.shader.CustomRenderPipelines.getShadowEntityPipeline(renderType.pipeline()) != null) {
                this.orderedCollector.submitItem(poseStack, itemDisplayContext, light, overlay, seed, tints, quads, renderType, foilType);
            }
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        }

        @Override
        public void submitParticleGroup(net.minecraft.client.renderer.SubmitNodeCollector.ParticleGroupRenderer particleGroupRenderer) {
        }
    }

}
