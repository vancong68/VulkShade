package net.vulkanmod.vulkan.pass;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.PipelineManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.profiling.GpuFrameProfiler;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;

public class DefaultMainPass implements MainPass {
    private static final Logger LOGGER = LogManager.getLogger("DefaultMainPass");

    public static DefaultMainPass create() {
        return new DefaultMainPass();
    }

    private final SwapChain swapChain;

    private RenderPass swapChainMainRenderPass;
    private RenderPass swapChainAuxRenderPass;
    private RenderPass sceneMainRenderPass;
    private RenderPass sceneAuxRenderPass;
    private RenderPass fsrUpscaleRenderPass;
    private RenderPass tonemapRenderPass;

    private Framebuffer sceneFramebuffer;
    private Framebuffer fsrUpscaleFramebuffer;
    private Framebuffer tonemapFramebuffer;

    private GpuTexture[] swapChainColorAttachmentTextures;
    private GpuTextureView[] swapChainColorAttachmentTextureViews;
    private GpuTexture swapChainDepthAttachmentTexture;

    private GpuTexture sceneColorTexture;
    private GpuTextureView sceneColorTextureView;
    private GpuTexture sceneDepthTexture;

    private GpuTexture fsrUpscaleColorTexture;
    private GpuTextureView fsrUpscaleColorTextureView;

    private GpuTexture tonemapColorTexture;
    private GpuTextureView tonemapColorTextureView;

    private boolean renderingToSwapChain = true;
    private boolean fsrResolvedThisFrame = false;

    DefaultMainPass() {
        this.swapChain = Renderer.getInstance().getSwapChain();

        recreateRenderPasses();
        createAttachmentTextures();
        ensureFsrResources();
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        ensureFsrResources();

        this.renderingToSwapChain = !isFsrEnabled();
        this.fsrResolvedThisFrame = false;

        Renderer renderer = Renderer.getInstance();
        renderer.beginRenderPass(getMainRenderPass(), getActiveFramebuffer());
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        long startNanos = System.nanoTime();

        Renderer.getInstance().endRenderPass(commandBuffer);

        if (isFsrEnabled() && !this.renderingToSwapChain) {
            resolveForGui(commandBuffer);
            Renderer.getInstance().endRenderPass(commandBuffer);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        }

        if (Renderer.getInstance().getGpuProfiler() != null) {
            Renderer.getInstance().getGpuProfiler().endFrame(commandBuffer);
        }

        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer:" + result);
        }
    }

    @Override
    public void cleanUp() {
        cleanupSwapChainAttachmentTextures();
        cleanupFsrResources();
        destroyRenderPasses();
    }

    @Override
    public void onResize() {
        cleanupSwapChainAttachmentTextures();
        cleanupFsrResources();
        destroyRenderPasses();

        recreateRenderPasses();
        createAttachmentTextures();
        ensureFsrResources();
    }

    @Override
    public void rebindMainTarget() {
        Renderer renderer = Renderer.getInstance();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        RenderPass targetRenderPass = getAuxRenderPass();

        if (renderer.getBoundRenderPass() == targetRenderPass) {
            return;
        }

        renderer.endRenderPass(commandBuffer);
        renderer.beginRenderPass(targetRenderPass, getActiveFramebuffer());
    }

    @Override
    public void bindAsTexture() {
        Renderer renderer = Renderer.getInstance();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        if (renderer.getBoundFramebuffer() == getActiveFramebuffer()) {
            renderer.endRenderPass(commandBuffer);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            getColorAttachmentImage().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(getColorAttachmentImage());
    }

    @Override
    public GpuTexture getColorAttachment() {
        return this.renderingToSwapChain ? this.swapChainColorAttachmentTextures[Renderer.getCurrentImage()] : this.sceneColorTexture;
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.renderingToSwapChain ? this.swapChainColorAttachmentTextureViews[Renderer.getCurrentImage()] : this.sceneColorTextureView;
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.renderingToSwapChain ? this.swapChainDepthAttachmentTexture : this.sceneDepthTexture;
    }

    @Override
    public VulkanImage getColorAttachmentImage() {
        return this.renderingToSwapChain ? this.swapChain.getColorAttachment() : this.sceneFramebuffer.getColorAttachment();
    }

    @Override
    public VulkanImage getDepthAttachmentImage() {
        return this.renderingToSwapChain ? this.swapChain.getDepthAttachment() : this.sceneFramebuffer.getDepthAttachment();
    }

    @Override
    public int getRenderWidth() {
        return this.renderingToSwapChain ? this.swapChain.getWidth() : getSceneRenderWidth();
    }

    @Override
    public int getRenderHeight() {
        return this.renderingToSwapChain ? this.swapChain.getHeight() : getSceneRenderHeight();
    }

    @Override
    public int getSceneRenderWidth() {
        return this.sceneFramebuffer != null ? this.sceneFramebuffer.getWidth() : this.swapChain.getWidth();
    }

    @Override
    public int getSceneRenderHeight() {
        return this.sceneFramebuffer != null ? this.sceneFramebuffer.getHeight() : this.swapChain.getHeight();
    }

    @Override
    public int getOutputWidth() {
        return this.swapChain.getWidth();
    }

    @Override
    public int getOutputHeight() {
        return this.swapChain.getHeight();
    }

    @Override
    public boolean isFsrEnabled() {
        return shouldRenderWorldWithFsr() && this.sceneFramebuffer != null && this.fsrUpscaleFramebuffer != null;
    }

    @Override
    public void resolveForGui(VkCommandBuffer commandBuffer) {
        if (!isFsrEnabled() || this.renderingToSwapChain) {
            return;
        }

        Renderer renderer = Renderer.getInstance();
        GpuFrameProfiler gpuProfiler = renderer.getGpuProfiler();

        renderer.endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.sceneFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        if (gpuProfiler != null) {
            gpuProfiler.beginStage(GpuFrameProfiler.Stage.FSR_EASU);
        }
        renderer.beginRenderPass(this.fsrUpscaleRenderPass, this.fsrUpscaleFramebuffer);
        drawFullscreenPass(PipelineManager.getFsrEasuPipeline(), this.sceneColorTextureView);
        renderer.endRenderPass(commandBuffer);
        if (gpuProfiler != null) {
            gpuProfiler.endStage(GpuFrameProfiler.Stage.FSR_EASU);
        }

        GraphicsPipeline finalPipeline = Initializer.CONFIG.fsrSharpness > 0
                ? PipelineManager.getFsrRcasPipeline()
                : PipelineManager.getFastBlitPipeline();

        if (gpuProfiler != null) {
            gpuProfiler.beginStage(GpuFrameProfiler.Stage.FSR_RCAS);
        }
        renderer.beginRenderPass(this.tonemapRenderPass, this.tonemapFramebuffer);
        drawFullscreenPass(finalPipeline, this.fsrUpscaleColorTextureView);
        renderer.endRenderPass(commandBuffer);
        if (gpuProfiler != null) {
            gpuProfiler.endStage(GpuFrameProfiler.Stage.FSR_RCAS);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.tonemapFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        if (gpuProfiler != null) {
            gpuProfiler.beginStage(GpuFrameProfiler.Stage.TONEMAP);
        }
        renderer.beginRenderPass(this.swapChainMainRenderPass, this.swapChain);
        drawFullscreenPass(PipelineManager.getTonemapPipeline(), this.tonemapColorTextureView);
        renderer.endRenderPass(commandBuffer);
        if (gpuProfiler != null) {
            gpuProfiler.endStage(GpuFrameProfiler.Stage.TONEMAP);
        }

        restoreFullscreenState();
        renderer.beginRenderPass(this.swapChainAuxRenderPass, this.swapChain);
        this.renderingToSwapChain = true;
        this.fsrResolvedThisFrame = true;
    }

    private void drawFullscreenPass(GraphicsPipeline pipeline, GpuTextureView inputTextureView) {
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);

        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.disableCull();
        VRenderSystem.disableBlend();
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);

        RenderSystem.setShaderTexture(0, inputTextureView);
        VTextureSelector.bindShaderTextures(pipeline);
        renderer.uploadAndBindUBOs(pipeline);

        VK11.vkCmdDraw(Renderer.getCommandBuffer(), 3, 1, 0, 0);
    }

    private static void restoreFullscreenState() {
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.enableCull();
    }

    private Framebuffer getActiveFramebuffer() {
        return this.renderingToSwapChain ? this.swapChain : this.sceneFramebuffer;
    }

    private RenderPass getMainRenderPass() {
        return this.renderingToSwapChain ? this.swapChainMainRenderPass : this.sceneMainRenderPass;
    }

    private RenderPass getAuxRenderPass() {
        return this.renderingToSwapChain ? this.swapChainAuxRenderPass : this.sceneAuxRenderPass;
    }

    private boolean shouldRenderWorldWithFsr() {
        return Initializer.CONFIG.fsrEnabled
                && Minecraft.getInstance().level != null
                && this.swapChain.getWidth() > 0
                && this.swapChain.getHeight() > 0;
    }

    private int getInternalWidth() {
        int outputWidth = Math.max(1, this.swapChain.getWidth());
        return Math.max(1, Math.round(outputWidth * (Initializer.CONFIG.fsrInternalScale / 100.0f)));
    }

    private int getInternalHeight() {
        int outputHeight = Math.max(1, this.swapChain.getHeight());
        return Math.max(1, Math.round(outputHeight * (Initializer.CONFIG.fsrInternalScale / 100.0f)));
    }

    private void recreateRenderPasses() {
        createSwapChainRenderPasses();
        createSceneRenderPasses();
    }

    private void destroyRenderPasses() {
        if (this.swapChainMainRenderPass != null) {
            this.swapChainMainRenderPass.cleanUp();
            this.swapChainMainRenderPass = null;
        }
        if (this.swapChainAuxRenderPass != null) {
            this.swapChainAuxRenderPass.cleanUp();
            this.swapChainAuxRenderPass = null;
        }
        if (this.sceneMainRenderPass != null) {
            this.sceneMainRenderPass.cleanUp();
            this.sceneMainRenderPass = null;
        }
        if (this.sceneAuxRenderPass != null) {
            this.sceneAuxRenderPass.cleanUp();
            this.sceneAuxRenderPass = null;
        }
        if (this.fsrUpscaleRenderPass != null) {
            this.fsrUpscaleRenderPass.cleanUp();
            this.fsrUpscaleRenderPass = null;
        }
        if (this.tonemapRenderPass != null) {
            this.tonemapRenderPass.cleanUp();
            this.tonemapRenderPass = null;
        }
    }

    private void createSwapChainRenderPasses() {
        RenderPass.Builder builder = RenderPass.builder(this.swapChain);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        this.swapChainMainRenderPass = builder.build();

        builder = RenderPass.builder(this.swapChain);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        this.swapChainAuxRenderPass = builder.build();
    }

    private void createSceneRenderPasses() {
        if (this.sceneFramebuffer == null) {
            return;
        }

        RenderPass.Builder builder = RenderPass.builder(this.sceneFramebuffer);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        this.sceneMainRenderPass = builder.build();

        builder = RenderPass.builder(this.sceneFramebuffer);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        this.sceneAuxRenderPass = builder.build();

        builder = RenderPass.builder(this.fsrUpscaleFramebuffer);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.fsrUpscaleRenderPass = builder.build();

        builder = RenderPass.builder(this.tonemapFramebuffer);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.tonemapRenderPass = builder.build();
    }

    private void createAttachmentTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
        var swapChainImages = this.swapChain.getImages();

        if (this.swapChain.getWidth() == 0 || this.swapChain.getHeight() == 0) {
            return;
        }

        int imageCount = swapChainImages.size();
        this.swapChainColorAttachmentTextures = new GpuTexture[imageCount];
        this.swapChainColorAttachmentTextureViews = new GpuTextureView[imageCount];

        for (int i = 0; i < imageCount; ++i) {
            VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(swapChainImages.get(i));
            GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
            this.swapChainColorAttachmentTextures[i] = attachmentTexture;
            this.swapChainColorAttachmentTextureViews[i] = attachmentTextureView;
        }

        this.swapChainDepthAttachmentTexture = device.gpuTextureFromVulkanImage(this.swapChain.getDepthAttachment());
    }

    private void cleanupSwapChainAttachmentTextures() {
        if (this.swapChainColorAttachmentTextureViews != null) {
            for (GpuTextureView textureView : this.swapChainColorAttachmentTextureViews) {
                if (textureView != null) {
                    textureView.close();
                }
            }
            this.swapChainColorAttachmentTextureViews = null;
        }
    }

    private void ensureFsrResources() {
        if (!Initializer.CONFIG.fsrEnabled || this.swapChain.getWidth() <= 0 || this.swapChain.getHeight() <= 0) {
            cleanupFsrResources();
            return;
        }

        int internalWidth = getInternalWidth();
        int internalHeight = getInternalHeight();
        int outputWidth = this.swapChain.getWidth();
        int outputHeight = this.swapChain.getHeight();

        boolean sceneMatches = this.sceneFramebuffer != null
                && this.sceneFramebuffer.getWidth() == internalWidth
                && this.sceneFramebuffer.getHeight() == internalHeight;
        boolean upscaleMatches = this.fsrUpscaleFramebuffer != null
                && this.fsrUpscaleFramebuffer.getWidth() == outputWidth
                && this.fsrUpscaleFramebuffer.getHeight() == outputHeight;
        boolean tonemapMatches = this.tonemapFramebuffer != null
                && this.tonemapFramebuffer.getWidth() == outputWidth
                && this.tonemapFramebuffer.getHeight() == outputHeight;

        if (sceneMatches && upscaleMatches && tonemapMatches) {
            return;
        }

        cleanupFsrResources();
        createFsrResources(internalWidth, internalHeight, outputWidth, outputHeight);
    }

    private void createFsrResources(int internalWidth, int internalHeight, int outputWidth, int outputHeight) {
        VulkanImage sceneColorAttachment = VulkanImage.builder(internalWidth, internalHeight)
                                                      .setName("FSR Scene Color")
                                                      .setFormat(this.swapChain.getColorAttachment().format)
                                                      .setUsage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                                                      .setLinearFiltering(true)
                                                      .setClamp(true)
                                                      .createVulkanImage();
        VulkanImage sceneDepthAttachment = VulkanImage.builder(internalWidth, internalHeight)
                                                      .setName("FSR Scene Depth")
                                                      .setFormat(this.swapChain.getDepthAttachment().format)
                                                      .setUsage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                                                      .setLinearFiltering(false)
                                                      .setClamp(true)
                                                      .createVulkanImage();

        this.sceneFramebuffer = Framebuffer.builder(sceneColorAttachment, sceneDepthAttachment).build();

        VulkanImage upscaleColorAttachment = VulkanImage.builder(outputWidth, outputHeight)
                                                        .setName("FSR Upscale Color")
                                                        .setFormat(this.swapChain.getColorAttachment().format)
                                                        .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                                                        .setLinearFiltering(true)
                                                        .setClamp(true)
                                                        .createVulkanImage();
        this.fsrUpscaleFramebuffer = Framebuffer.builder(upscaleColorAttachment, null).build();

        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
        this.sceneColorTexture = device.gpuTextureFromVulkanImage(sceneColorAttachment);
        this.sceneColorTextureView = device.createTextureView((VkGpuTexture) this.sceneColorTexture);
        this.sceneDepthTexture = device.gpuTextureFromVulkanImage(sceneDepthAttachment);

        this.fsrUpscaleColorTexture = device.gpuTextureFromVulkanImage(upscaleColorAttachment);
        this.fsrUpscaleColorTextureView = device.createTextureView((VkGpuTexture) this.fsrUpscaleColorTexture);

        VulkanImage tonemapColorAttachment = VulkanImage.builder(outputWidth, outputHeight)
                                                        .setName("Tonemap Color")
                                                        .setFormat(this.swapChain.getColorAttachment().format)
                                                        .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                                                        .setLinearFiltering(true)
                                                        .setClamp(true)
                                                        .createVulkanImage();
        this.tonemapFramebuffer = Framebuffer.builder(tonemapColorAttachment, null).build();
        this.tonemapColorTexture = device.gpuTextureFromVulkanImage(tonemapColorAttachment);
        this.tonemapColorTextureView = device.createTextureView((VkGpuTexture) this.tonemapColorTexture);

        if (this.sceneMainRenderPass != null || this.sceneAuxRenderPass != null || this.fsrUpscaleRenderPass != null || this.tonemapRenderPass != null) {
            if (this.sceneMainRenderPass != null) {
                this.sceneMainRenderPass.cleanUp();
            }
            if (this.sceneAuxRenderPass != null) {
                this.sceneAuxRenderPass.cleanUp();
            }
            if (this.fsrUpscaleRenderPass != null) {
                this.fsrUpscaleRenderPass.cleanUp();
            }
            if (this.tonemapRenderPass != null) {
                this.tonemapRenderPass.cleanUp();
                this.tonemapRenderPass = null;
            }
        }

        createSceneRenderPasses();
    }

    private void cleanupFsrResources() {
        if (this.sceneColorTextureView != null) {
            this.sceneColorTextureView.close();
            this.sceneColorTextureView = null;
        }
        if (this.fsrUpscaleColorTextureView != null) {
            this.fsrUpscaleColorTextureView.close();
            this.fsrUpscaleColorTextureView = null;
        }
        if (this.sceneColorTexture != null) {
            this.sceneColorTexture.close();
            this.sceneColorTexture = null;
        }
        if (this.sceneDepthTexture != null) {
            this.sceneDepthTexture.close();
            this.sceneDepthTexture = null;
        }
        if (this.fsrUpscaleColorTexture != null) {
            this.fsrUpscaleColorTexture.close();
            this.fsrUpscaleColorTexture = null;
        }
        if (this.tonemapColorTextureView != null) {
            this.tonemapColorTextureView.close();
            this.tonemapColorTextureView = null;
        }
        if (this.tonemapColorTexture != null) {
            this.tonemapColorTexture.close();
            this.tonemapColorTexture = null;
        }
        if (this.sceneFramebuffer != null) {
            this.sceneFramebuffer.cleanUp();
            this.sceneFramebuffer = null;
        }
        if (this.fsrUpscaleFramebuffer != null) {
            this.fsrUpscaleFramebuffer.cleanUp();
            this.fsrUpscaleFramebuffer = null;
        }
        if (this.tonemapFramebuffer != null) {
            this.tonemapFramebuffer.cleanUp();
            this.tonemapFramebuffer = null;
        }
        if (this.sceneMainRenderPass != null) {
            this.sceneMainRenderPass.cleanUp();
            this.sceneMainRenderPass = null;
        }
        if (this.sceneAuxRenderPass != null) {
            this.sceneAuxRenderPass.cleanUp();
            this.sceneAuxRenderPass = null;
        }
        if (this.fsrUpscaleRenderPass != null) {
            this.fsrUpscaleRenderPass.cleanUp();
            this.fsrUpscaleRenderPass = null;
        }
        if (this.tonemapRenderPass != null) {
            this.tonemapRenderPass.cleanUp();
            this.tonemapRenderPass = null;
        }
    }
}
