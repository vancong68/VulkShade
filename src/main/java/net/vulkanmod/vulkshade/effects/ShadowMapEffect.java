package net.vulkanmod.vulkshade.effects;

import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.vulkan.VK10.*;

public class ShadowMapEffect {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-ShadowMap");

    private ShadowQuality quality = ShadowQuality.MEDIUM;
    private int shadowMapSize = 1024;
    private int cascadeCount = 1;
    private float shadowBias = 0.005f;

    private Framebuffer shadowFramebuffer;
    private VulkanImage shadowDepthTexture;

    private final float[] cascadeSplits = new float[4];
    private final Matrix4f[] cascadeViewMatrices = new Matrix4f[4];
    private final Matrix4f[] cascadeProjMatrices = new Matrix4f[4];
    private float[] cascadeSplitDepths = new float[4];
    private boolean needCascadeUpdate = true;

    public enum ShadowQuality {
        OFF,
        LOW,
        MEDIUM,
        HIGH,
        ULTRA
    }

    public ShadowMapEffect() {
    }

    public void initialize() {
        int size = getShadowSizeForQuality();
        createShadowMap(size, size);
    }

    public void resize(int newSize) {
        cleanup();
        this.shadowMapSize = newSize;
        initialize();
    }

    public void cleanup() {
        if (shadowFramebuffer != null) {
            shadowFramebuffer.cleanUp();
            shadowFramebuffer = null;
        }
        if (shadowDepthTexture != null) {
            shadowDepthTexture.free();
            shadowDepthTexture = null;
        }
    }

    public UBO createShadowUBO(int binding) {
        AlignedStruct.Builder builder = new AlignedStruct.Builder();
        builder.addUniformInfo(Uniform.createUniformInfo("mat4", "shadowModelView", 16));
        builder.addUniformInfo(Uniform.createUniformInfo("mat4", "shadowProjection", 16));
        builder.addUniformInfo(Uniform.createUniformInfo("vec3", "shadowLightPosition", 3));
        builder.addUniformInfo(Uniform.createUniformInfo("float", "shadowBias", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("float", "dayNightMix", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("int", "shadowQuality", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("int", "cascadeCount", 1));
        builder.addUniformInfo(Uniform.createUniformInfo("vec4", "cascadeSplits", 4));
        builder.addUniformInfo(Uniform.createUniformInfo("mat4", "cascadeViewMat[0]", 16));
        builder.addUniformInfo(Uniform.createUniformInfo("mat4", "cascadeViewMat[1]", 16));
        builder.addUniformInfo(Uniform.createUniformInfo("mat4", "cascadeViewMat[2]", 16));
        builder.addUniformInfo(Uniform.createUniformInfo("mat4", "cascadeViewMat[3]", 16));
        builder.addUniformInfo(Uniform.createUniformInfo("mat4", "cascadeProjMat[0]", 16));
        builder.addUniformInfo(Uniform.createUniformInfo("mat4", "cascadeProjMat[1]", 16));
        builder.addUniformInfo(Uniform.createUniformInfo("mat4", "cascadeProjMat[2]", 16));
        builder.addUniformInfo(Uniform.createUniformInfo("mat4", "cascadeProjMat[3]", 16));
        var ubo = builder.buildUBO(binding, -1);
        ubo.setUseGlobalBuffer(false);
        return ubo;
    }

    public Framebuffer getShadowFramebuffer() { return shadowFramebuffer; }
    public VulkanImage getShadowDepthTexture() { return shadowDepthTexture; }
    public int getShadowMapSize() { return shadowMapSize; }

    public void setQuality(ShadowQuality quality) {
        this.quality = quality;
        int newSize = getShadowSizeForQuality();
        if (newSize != shadowMapSize) {
            resize(newSize);
        }
    }

    public ShadowQuality getQuality() { return quality; }

    public void setCascadeCount(int count) {
        this.cascadeCount = Math.max(1, Math.min(4, count));
    }

    public int getCascadeCount() { return cascadeCount; }

    public void setShadowBias(float bias) { this.shadowBias = bias; }
    public float getShadowBias() { return shadowBias; }

    private int getShadowSizeForQuality() {
        return switch (quality) {
            case OFF -> 0;
            case LOW -> 512;
            case MEDIUM -> 1024;
            case HIGH -> 2048;
            case ULTRA -> 4096;
        };
    }

    private void createShadowMap(int width, int height) {
        if (width <= 0 || height <= 0) return;
        cleanup();

        this.shadowMapSize = width;

        this.shadowFramebuffer = Framebuffer.builder(width, height, 0, true)
            .setDepthLinearFiltering(false)
            .build();
        this.shadowDepthTexture = shadowFramebuffer.getDepthAttachment();

        for (int i = 0; i < 4; i++) {
            cascadeViewMatrices[i] = new Matrix4f();
            cascadeProjMatrices[i] = new Matrix4f();
        }

        needCascadeUpdate = true;
        LOGGER.info("Shadow map created: {}x{}", width, height);
    }

    public void updateCascadeSplits(float nearPlane, float farPlane, float lambda) {
        for (int i = 0; i < cascadeCount; i++) {
            float p = (float) (i + 1) / cascadeCount;
            float logSplit = nearPlane * (float) Math.pow(farPlane / nearPlane, p);
            float uniformSplit = nearPlane + (farPlane - nearPlane) * p;
            cascadeSplits[i] = logSplit * lambda + uniformSplit * (1.0f - lambda);
        }
        cascadeSplits[cascadeCount - 1] = farPlane;
        needCascadeUpdate = true;
    }

    public void computeCascadeMatrices(Vector3f lightDir, Matrix4f viewMatrix, Matrix4f projMatrix) {
        if (!needCascadeUpdate) return;

        float near = 0.05f;
        float far = Minecraft.getInstance().options.renderDistance().get() * 16.0f;
        updateCascadeSplits(near, far, 0.85f);

        for (int i = 0; i < cascadeCount; i++) {
            float prevSplit = i == 0 ? near : cascadeSplits[i - 1];
            float split = cascadeSplits[i];

            Matrix4f invView = new Matrix4f(viewMatrix).invert();

            Vector3f[] frustumCorners = new Vector3f[8];
            for (int j = 0; j < 8; j++) {
                float sx = (j & 1) == 0 ? -1.0f : 1.0f;
                float sy = (j & 2) == 0 ? -1.0f : 1.0f;
                float sz = (j & 4) == 0 ? prevSplit : split;
                float ndcZ = (sz + near) / (sz - near) * 0.5f + 0.5f;
                frustumCorners[j] = new Vector3f();
            }

            Vector3f center = new Vector3f();
            for (Vector3f corner : frustumCorners) {
                center.add(corner);
            }
            center.div(8.0f);

            Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
            if (Math.abs(lightDir.dot(up)) > 0.99f) {
                up.set(0.0f, 0.0f, 1.0f);
            }
            Vector3f right = new Vector3f(lightDir).cross(up).normalize();
            up = new Vector3f(right).cross(lightDir).normalize();

            cascadeViewMatrices[i].identity();
            cascadeViewMatrices[i].lookAt(
                new Vector3f(0.0f, 0.0f, 0.0f),
                lightDir,
                up
            );

            cascadeSplitDepths[i] = split;
        }

        needCascadeUpdate = false;
    }

    public float[] getCascadeSplits() { return cascadeSplits; }
    public Matrix4f getCascadeViewMatrix(int index) { return cascadeViewMatrices[index]; }
    public Matrix4f getCascadeProjMatrix(int index) { return cascadeProjMatrices[index]; }
    public float getCascadeSplitDepth(int index) { return cascadeSplitDepths[index]; }
    public void markCascadeDirty() { needCascadeUpdate = true; }
}
