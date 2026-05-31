package net.vulkanmod.vulkan;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.engine.VkGpuBuffer;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.util.ColorUtil;
import net.vulkanmod.vulkan.util.MappedBuffer;
import net.vulkanmod.vulkan.util.VUtil;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public abstract class VRenderSystem {
    private static final float DEFAULT_DEPTH_VALUE = 1.0f;

    private static long window;

    public static boolean depthTest = true;
    public static boolean depthMask = true;
    public static int depthFun = 515;
    public static int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    public static int polygonMode = VK_POLYGON_MODE_FILL;
    public static boolean canSetLineWidth = false;

    public static int colorMask = PipelineState.ColorMask.getColorMask(true, true, true, true);

    public static boolean cull = true;

    public static boolean logicOp = false;
    public static int logicOpFun = 0;

    public static float clearDepthValue = DEFAULT_DEPTH_VALUE;
    public static FloatBuffer clearColor = MemoryUtil.memCallocFloat(4);

    public static MappedBuffer modelViewMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer modelViewMatrixInverse = new MappedBuffer(16 * 4);
    public static MappedBuffer projectionMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer projectionMatrixInverse = new MappedBuffer(16 * 4);
    public static MappedBuffer TextureMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer MVP = new MappedBuffer(16 * 4);

    public static MappedBuffer modelOffset = new MappedBuffer(3 * 4);
    public static MappedBuffer lightDirection0 = new MappedBuffer(3 * 4);
    public static MappedBuffer lightDirection1 = new MappedBuffer(3 * 4);

    public static MappedBuffer shaderColor = new MappedBuffer(4 * 4);
    public static MappedBuffer shaderFogColor = new MappedBuffer(4 * 4);
    public static FogData fogData;

    public static MappedBuffer screenSize = new MappedBuffer(2 * 4);
    public static MappedBuffer fsrEasuCon0 = new MappedBuffer(4 * 4);
    public static MappedBuffer fsrEasuCon1 = new MappedBuffer(4 * 4);
    public static MappedBuffer fsrEasuCon2 = new MappedBuffer(4 * 4);
    public static MappedBuffer fsrEasuCon3 = new MappedBuffer(4 * 4);
    public static MappedBuffer fsrRcasCon = new MappedBuffer(4 * 4);

    public static float alphaCutout = 0.0f;

    // Sky uniforms
    public static MappedBuffer skyUpDirection = new MappedBuffer(3 * 4);
    public static MappedBuffer skyLeftDirection = new MappedBuffer(3 * 4);
    public static MappedBuffer skyLookDirection = new MappedBuffer(3 * 4);
    public static MappedBuffer skyCameraPosition = new MappedBuffer(3 * 4);
    public static MappedBuffer skySunDirection = new MappedBuffer(3 * 4);
    public static MappedBuffer skySunRightDirection = new MappedBuffer(3 * 4);
    public static MappedBuffer skySunUpDirection = new MappedBuffer(3 * 4);
    public static MappedBuffer skyMoonDirection = new MappedBuffer(3 * 4);
    public static MappedBuffer skyMoonRightDirection = new MappedBuffer(3 * 4);
    public static MappedBuffer skyMoonUpDirection = new MappedBuffer(3 * 4);
    public static MappedBuffer skySunsetColor = new MappedBuffer(4 * 4);
    public static MappedBuffer shadowModelView = new MappedBuffer(16 * 4);
    public static MappedBuffer shadowProjection = new MappedBuffer(16 * 4);
    public static MappedBuffer shadowLightPosition = new MappedBuffer(3 * 4);
    public static float skyCloudTime = 0.0f;
    public static float skyDayMoment = 0.25f;
    public static float skyDayMixer = 1.0f;
    public static float skyNightMixer = 0.0f;
    public static float skyRainStrength = 0.0f;
    public static float shadowDayNightMix = 1.0f;
    public static int skyMoonPhase = 0;
    public static float skyStarBrightness = 0.0f;
    public static int eyeInWater = 0;

    private static boolean depthBiasEnabled = false;
    private static float depthBiasConstant = 0.0f;
    private static float depthBiasSlope = 0.0f;

    public static void initRenderer() {
        Vulkan.initVulkan(window);

        net.vulkanmod.vulkshade.VulkShade.getInstance().onVulkanInitComplete();

        setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static MappedBuffer getScreenSize() {
        updateScreenSize();
        return screenSize;
    }

    public static void updateScreenSize() {
        Renderer renderer = Renderer.getInstance();
        MainPass mainPass = renderer != null ? renderer.getMainPass() : null;
        if (mainPass != null && mainPass.getRenderWidth() > 0 && mainPass.getRenderHeight() > 0) {
            screenSize.putFloat(0, (float) mainPass.getRenderWidth());
            screenSize.putFloat(4, (float) mainPass.getRenderHeight());
            return;
        }

        Window window = Minecraft.getInstance().getWindow();
        screenSize.putFloat(0, (float) window.getWidth());
        screenSize.putFloat(4, (float) window.getHeight());
    }

    public static MappedBuffer getFsrEasuCon0() {
        updateFsrConstants();
        return fsrEasuCon0;
    }

    public static MappedBuffer getFsrEasuCon1() {
        updateFsrConstants();
        return fsrEasuCon1;
    }

    public static MappedBuffer getFsrEasuCon2() {
        updateFsrConstants();
        return fsrEasuCon2;
    }

    public static MappedBuffer getFsrEasuCon3() {
        updateFsrConstants();
        return fsrEasuCon3;
    }

    public static MappedBuffer getFsrRcasCon() {
        updateFsrConstants();
        return fsrRcasCon;
    }

    private static void updateFsrConstants() {
        Renderer renderer = Renderer.getInstance();
        MainPass mainPass = renderer != null ? renderer.getMainPass() : null;
        if (mainPass == null) {
            putVec4(fsrEasuCon0, 1.0f, 1.0f, 0.0f, 0.0f);
            putVec4(fsrEasuCon1, 1.0f, 1.0f, 1.0f, -1.0f);
            putVec4(fsrEasuCon2, -1.0f, 2.0f, 1.0f, 2.0f);
            putVec4(fsrEasuCon3, 0.0f, 4.0f, 0.0f, 0.0f);
            putVec4(fsrRcasCon, 1.0f, 0.0f, 0.0f, 0.0f);
            return;
        }

        float inputWidth = Math.max(1.0f, mainPass.getSceneRenderWidth());
        float inputHeight = Math.max(1.0f, mainPass.getSceneRenderHeight());
        float outputWidth = Math.max(1.0f, mainPass.getOutputWidth());
        float outputHeight = Math.max(1.0f, mainPass.getOutputHeight());

        float invInputWidth = 1.0f / inputWidth;
        float invInputHeight = 1.0f / inputHeight;

        putVec4(
                fsrEasuCon0,
                inputWidth / outputWidth,
                inputHeight / outputHeight,
                (0.5f * inputWidth / outputWidth) - 0.5f,
                (0.5f * inputHeight / outputHeight) - 0.5f
        );
        putVec4(fsrEasuCon1, invInputWidth, invInputHeight, invInputWidth, -invInputHeight);
        putVec4(fsrEasuCon2, -invInputWidth, 2.0f * invInputHeight, invInputWidth, 2.0f * invInputHeight);
        putVec4(fsrEasuCon3, 0.0f, 4.0f * invInputHeight, 0.0f, 0.0f);

        float sharpnessStops = 2.0f * (1.0f - (Math.max(0, Math.min(100, Initializer.CONFIG.fsrSharpness)) / 100.0f));
        float sharpness = (float) Math.pow(2.0, -sharpnessStops);
        putVec4(fsrRcasCon, sharpness, 0.0f, 0.0f, 0.0f);
    }

    private static void putVec4(MappedBuffer buffer, float x, float y, float z, float w) {
        buffer.putFloat(0, x);
        buffer.putFloat(4, y);
        buffer.putFloat(8, z);
        buffer.putFloat(12, w);
    }

    public static void setWindow(long window) {
        VRenderSystem.window = window;
    }

    public static ByteBuffer getModelOffset() {
        return modelOffset.buffer;
    }

    public static int maxSupportedTextureSize() {
        return DeviceManager.deviceProperties.limits().maxImageDimension2D();
    }

    public static void applyMVP(Matrix4f MV, Matrix4f P) {
        applyModelViewMatrix(MV);
        applyProjectionMatrix(P);
        calculateMVP();
    }

    public static void applyModelViewMatrix(Matrix4f mat) {
        mat.get(modelViewMatrix.buffer.asFloatBuffer());
        new Matrix4f(mat).invert().get(modelViewMatrixInverse.buffer.asFloatBuffer());
    }

    public static void applyProjectionMatrix(Matrix4f mat) {
        mat.get(projectionMatrix.buffer.asFloatBuffer());
        new Matrix4f(mat).invert().get(projectionMatrixInverse.buffer.asFloatBuffer());
    }

    public static void applyProjectionMatrix(GpuBufferSlice bufferSlice) {
        long ptr = ((VkGpuBuffer) bufferSlice.buffer()).getBuffer().getDataPtr();
        ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(ptr + bufferSlice.offset(), bufferSlice.length());
        Matrix4f matrix4f = new Matrix4f().set(byteBuffer);

        matrix4f.get(projectionMatrix.buffer.asFloatBuffer());
        matrix4f.invert(new Matrix4f()).get(projectionMatrixInverse.buffer.asFloatBuffer());
    }

    public static void calculateMVP() {
        org.joml.Matrix4f MV = new org.joml.Matrix4f(modelViewMatrix.buffer.asFloatBuffer());
        org.joml.Matrix4f P = new org.joml.Matrix4f(projectionMatrix.buffer.asFloatBuffer());

        P.mul(MV).get(MVP.buffer);
    }

    public static void setTextureMatrix(Matrix4f mat) {
        mat.get(TextureMatrix.buffer.asFloatBuffer());
    }

    public static MappedBuffer getTextureMatrix() {
        return TextureMatrix;
    }

    public static MappedBuffer getModelViewMatrix() {
        return modelViewMatrix;
    }

    public static MappedBuffer getProjectionMatrix() {
        return projectionMatrix;
    }

    public static MappedBuffer getModelViewMatrixInverse() {
        return modelViewMatrixInverse;
    }

    public static MappedBuffer getProjectionMatrixInverse() {
        return projectionMatrixInverse;
    }

    public static MappedBuffer getMVP() {
        return MVP;
    }

    public static void setModelOffset(float x, float y, float z) {
        long ptr = modelOffset.ptr;
        VUtil.UNSAFE.putFloat(ptr, x);
        VUtil.UNSAFE.putFloat(ptr + 4, y);
        VUtil.UNSAFE.putFloat(ptr + 8, z);
    }

    public static void setShaderColor(float f1, float f2, float f3, float f4) {
        ColorUtil.setRGBA_Buffer(shaderColor, f1, f2, f3, f4);
    }

    public static void setShaderFogColor(float f1, float f2, float f3, float f4) {
        ColorUtil.setRGBA_Buffer(shaderFogColor, f1, f2, f3, f4);
    }

    public static MappedBuffer getShaderColor() {
        return shaderColor;
    }

    public static MappedBuffer getShaderFogColor() {
        return shaderFogColor;
    }

    public static FogData getFogData() {
        return fogData;
    }

    public static void setClearColor(float f1, float f2, float f3, float f4) {
        ColorUtil.setRGBA_Buffer(clearColor, f1, f2, f3, f4);
    }

    public static void clear(int mask) {
        Renderer.clearAttachments(mask);
    }

    public static void clearDepth(double depth) {
        clearDepthValue = (float) depth;
    }

    // Pipeline state

    public static void disableDepthTest() {
        depthTest = false;
    }

    public static void depthMask(boolean b) {
        depthMask = b;
    }

    public static void setPrimitiveTopologyGL(final int mode) {
        VRenderSystem.topology = switch (mode) {
            case GL11.GL_LINES, GL11.GL_LINE_STRIP  -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case GL11.GL_TRIANGLE_FAN, GL11.GL_TRIANGLES, GL11.GL_TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            default -> throw new RuntimeException(String.format("Unknown GL primitive topology: %s", mode));
        };
    }

    public static void setPolygonModeGL(final int mode) {
        VRenderSystem.polygonMode = switch (mode) {
            case GL11.GL_POINT -> VK_POLYGON_MODE_POINT;
            case GL11.GL_LINE -> VK_POLYGON_MODE_LINE;
            case GL11.GL_FILL -> VK_POLYGON_MODE_FILL;
            default -> throw new RuntimeException(String.format("Unknown GL polygon mode: %s", mode));
        };
    }

    public static void setLineWidth(final float width) {
        if (canSetLineWidth) {
            Renderer.setLineWidth(width);
        }
    }

    public static void colorMask(boolean b, boolean b1, boolean b2, boolean b3) {
        colorMask = PipelineState.ColorMask.getColorMask(b, b1, b2, b3);
    }

    public static int getColorMask() {
        return colorMask;
    }

    public static void enableDepthTest() {
        depthTest = true;
    }

    public static void enableCull() {
        cull = true;
    }

    public static void disableCull() {
        cull = false;
    }

    public static void depthFunc(int depthFun) {
        VRenderSystem.depthFun = depthFun;
    }

    public static void enableBlend() {
        PipelineState.blendInfo.enabled = true;
    }

    public static void disableBlend() {
        PipelineState.blendInfo.enabled = false;
    }

    public static void blendFunc(int srcFactor, int dstFactor) {
        PipelineState.blendInfo.setBlendFunction(srcFactor, dstFactor);
    }

    public static void blendFuncSeparate(int srcFactorRGB, int dstFactorRGB, int srcFactorAlpha, int dstFactorAlpha) {
        PipelineState.blendInfo.setBlendFuncSeparate(srcFactorRGB, dstFactorRGB, srcFactorAlpha, dstFactorAlpha);
    }

    public static void blendOp(int op) {
        PipelineState.blendInfo.setBlendOp(op);
    }

    public static void enableColorLogicOp() {
        logicOp = true;
    }

    public static void disableColorLogicOp() {
        logicOp = false;
    }

    public static void logicOp(int glLogicOp) {
        logicOpFun = glLogicOp;
    }

    public static void polygonOffset(float slope, float biasConstant) {
        if (depthBiasConstant != biasConstant || depthBiasSlope != slope) {
            depthBiasConstant = biasConstant;
            depthBiasSlope = slope;

            Renderer.setDepthBias(depthBiasConstant, depthBiasSlope);
        }
    }

    public static void enablePolygonOffset() {
        if (!depthBiasEnabled) {
            Renderer.setDepthBias(depthBiasConstant, depthBiasSlope);
            depthBiasEnabled = true;
        }
    }

    public static void disablePolygonOffset() {
        if (depthBiasEnabled) {
            Renderer.setDepthBias(0.0F, 0.0F);
            depthBiasEnabled = false;
        }
    }

}
