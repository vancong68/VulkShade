package net.vulkanmod.render.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkyRenderer {
    private static final float SUN_PATH_ROTATION_DEGREES = -25.0f;
    private static final float SHADOW_DISTANCE = 105.0f;
    private static final float SHADOW_INTERVAL_SIZE = 3.0f;
    private static final float SHADOW_BASE_TRANSLATE_Z = -100.0f;
    private static final float SHADOW_NEAR = -100.05f;
    private static final float SHADOW_FAR = 156.0f;

    private static final Logger LOGGER = LoggerFactory.getLogger("VulkShade-Sky");
    private static final ResourceLocation CLOUD_TEXTURE_LOCATION = ResourceLocation.fromNamespaceAndPath("vulkanmod", "textures/effects/makeup_clouds.png");
    private static final ResourceLocation SUN_TEXTURE_LOCATION = ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    private static final ResourceLocation MOON_TEXTURE_LOCATION = ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");
    private boolean loggedOnce = false;

    public void renderSky(ClientLevel level, float partialTick, Camera camera) {
        renderSky(level, partialTick, camera, Float.NaN);
    }

    public void renderSky(ClientLevel level, float partialTick, Camera camera, float timeOfDayOverride) {
        renderSky(level, partialTick, camera, timeOfDayOverride, null);
    }

    public void renderSky(ClientLevel level, float partialTick, Camera camera, float timeOfDayOverride, Matrix4f projection) {
        if (level == null) return;

        updateSkyState(level, partialTick, camera, timeOfDayOverride, projection);

        if (!loggedOnce) {
            LOGGER.info("[VulkShade-Sky] renderSky called: dayMoment={}, dayMixer={}, nightMixer={}, rain={}, starBright={}",
                VRenderSystem.skyDayMoment, VRenderSystem.skyDayMixer, VRenderSystem.skyNightMixer,
                VRenderSystem.skyRainStrength, VRenderSystem.skyStarBrightness);
            loggedOnce = true;
        }

        GraphicsPipeline skyPipeline = PipelineManager.getSkyPipeline();

        Renderer renderer = Renderer.getInstance();
        renderer.getMainPass().rebindMainTarget();

        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.disableCull();
        VRenderSystem.disableBlend();
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);

        renderer.bindGraphicsPipeline(skyPipeline);
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        AbstractTexture cloudTexture = textureManager.getTexture(CLOUD_TEXTURE_LOCATION);
        cloudTexture.setUseMipmaps(false);
        cloudTexture.setFilter(true, false);
        cloudTexture.setClamp(false);
        RenderSystem.setShaderTexture(0, cloudTexture.getTextureView());

        AbstractTexture sunTexture = textureManager.getTexture(SUN_TEXTURE_LOCATION);
        sunTexture.setUseMipmaps(false);
        sunTexture.setFilter(false, false);
        sunTexture.setClamp(true);
        RenderSystem.setShaderTexture(1, sunTexture.getTextureView());

        AbstractTexture moonTexture = textureManager.getTexture(MOON_TEXTURE_LOCATION);
        moonTexture.setUseMipmaps(false);
        moonTexture.setFilter(false, false);
        moonTexture.setClamp(true);
        RenderSystem.setShaderTexture(2, moonTexture.getTextureView());

        VTextureSelector.bindShaderTextures(skyPipeline);
        renderer.uploadAndBindUBOs(skyPipeline);

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        VK11.vkCmdDraw(commandBuffer, 3, 1, 0, 0);

        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.enableCull();
    }

    public static void updateSkyState(ClientLevel level, float partialTick, Camera camera) {
        updateSkyState(level, partialTick, camera, Float.NaN);
    }

    public static void updateSkyState(ClientLevel level, float partialTick, Camera camera, float timeOfDayOverride) {
        updateSkyState(level, partialTick, camera, timeOfDayOverride, null);
    }

    public static void updateSkyState(ClientLevel level, float partialTick, Camera camera, Matrix4f projection) {
        updateSkyState(level, partialTick, camera, Float.NaN, projection);
    }

    public static void updateSkyState(ClientLevel level, float partialTick, Camera camera, float timeOfDayOverride, Matrix4f projection) {
        if (level == null || camera == null) {
            return;
        }

        Camera.NearPlane nearPlane = camera.getNearPlane();
        Vec3 forward = nearPlane.getPointOnPlane(0.0f, 0.0f);
        Vec3 left = nearPlane.getPointOnPlane(-1.0f, 0.0f).subtract(forward);
        Vec3 up = nearPlane.getPointOnPlane(0.0f, 1.0f).subtract(forward);

        if (projection != null) {
            double nearPlaneDistance = forward.length();
            double leftLength = left.length();
            double upLength = up.length();
            double targetLeftLength = projectedNearPlaneHalfExtent(projection.m00(), nearPlaneDistance);
            double targetUpLength = projectedNearPlaneHalfExtent(projection.m11(), nearPlaneDistance);

            if (targetLeftLength > 0.0 && leftLength > 0.0) {
                left = left.scale(targetLeftLength / leftLength);
            }
            if (targetUpLength > 0.0 && upLength > 0.0) {
                up = up.scale(targetUpLength / upLength);
            }
        }

        Vec3 cameraPosition = camera.getPosition();

        VRenderSystem.skyLookDirection.putFloat(0, (float) forward.x);
        VRenderSystem.skyLookDirection.putFloat(4, (float) forward.y);
        VRenderSystem.skyLookDirection.putFloat(8, (float) forward.z);

        VRenderSystem.skyLeftDirection.putFloat(0, (float) left.x);
        VRenderSystem.skyLeftDirection.putFloat(4, (float) left.y);
        VRenderSystem.skyLeftDirection.putFloat(8, (float) left.z);

        VRenderSystem.skyUpDirection.putFloat(0, (float) up.x);
        VRenderSystem.skyUpDirection.putFloat(4, (float) up.y);
        VRenderSystem.skyUpDirection.putFloat(8, (float) up.z);

        VRenderSystem.skyCameraPosition.putFloat(0, (float) cameraPosition.x);
        VRenderSystem.skyCameraPosition.putFloat(4, (float) cameraPosition.y);
        VRenderSystem.skyCameraPosition.putFloat(8, (float) cameraPosition.z);

        // Use vanilla's visible-sun time-of-day angle for the celestial path.
        // In 1.21.10, ClientLevel.getSunAngle() returns radians for sunrise/sunset math,
        // not the degree scalar used by the sun/moon quad rotation.
        float skyAngle = Float.isNaN(timeOfDayOverride) ? level.getTimeOfDay(partialTick) : timeOfDayOverride;
        float sunAngleDegrees = skyAngle * 360.0f;
        float moonAngleDegrees = ((skyAngle + 0.5f) % 1.0f) * 360.0f;
        Vector4f sunPosition = new Vector4f(0.0f, 100.0f, 0.0f, 0.0f);
        Vector4f sunRight = new Vector4f(1.0f, 0.0f, 0.0f, 0.0f);
        Vector4f sunUp = new Vector4f(0.0f, 0.0f, 1.0f, 0.0f);
        Vector4f moonPosition = new Vector4f(0.0f, 100.0f, 0.0f, 0.0f);
        Vector4f moonRight = new Vector4f(1.0f, 0.0f, 0.0f, 0.0f);
        Vector4f moonUp = new Vector4f(0.0f, 0.0f, 1.0f, 0.0f);
        Matrix4f celestial = new Matrix4f()
            .identity()
            .rotate(Axis.YP.rotationDegrees(-90.0f))
            .rotate(Axis.ZP.rotationDegrees(SUN_PATH_ROTATION_DEGREES))
            .rotate(Axis.XP.rotationDegrees(sunAngleDegrees));
        Matrix4f moonCelestial = new Matrix4f()
            .identity()
            .rotate(Axis.YP.rotationDegrees(-90.0f))
            .rotate(Axis.ZP.rotationDegrees(SUN_PATH_ROTATION_DEGREES))
            .rotate(Axis.XP.rotationDegrees(moonAngleDegrees));
        celestial.transform(sunPosition);
        celestial.transform(sunRight);
        celestial.transform(sunUp);
        moonCelestial.transform(moonPosition);
        moonCelestial.transform(moonRight);
        moonCelestial.transform(moonUp);

        VRenderSystem.skySunDirection.putFloat(0, sunPosition.x);
        VRenderSystem.skySunDirection.putFloat(4, sunPosition.y);
        VRenderSystem.skySunDirection.putFloat(8, sunPosition.z);
        VRenderSystem.skySunRightDirection.putFloat(0, sunRight.x);
        VRenderSystem.skySunRightDirection.putFloat(4, sunRight.y);
        VRenderSystem.skySunRightDirection.putFloat(8, sunRight.z);
        VRenderSystem.skySunUpDirection.putFloat(0, sunUp.x);
        VRenderSystem.skySunUpDirection.putFloat(4, sunUp.y);
        VRenderSystem.skySunUpDirection.putFloat(8, sunUp.z);
        VRenderSystem.skyMoonDirection.putFloat(0, moonPosition.x);
        VRenderSystem.skyMoonDirection.putFloat(4, moonPosition.y);
        VRenderSystem.skyMoonDirection.putFloat(8, moonPosition.z);
        VRenderSystem.skyMoonRightDirection.putFloat(0, moonRight.x);
        VRenderSystem.skyMoonRightDirection.putFloat(4, moonRight.y);
        VRenderSystem.skyMoonRightDirection.putFloat(8, moonRight.z);
        VRenderSystem.skyMoonUpDirection.putFloat(0, moonUp.x);
        VRenderSystem.skyMoonUpDirection.putFloat(4, moonUp.y);
        VRenderSystem.skyMoonUpDirection.putFloat(8, moonUp.z);
        VRenderSystem.skyCloudTime = ((level.getGameTime() % 120000L) + partialTick) / 20.0f;

        // Convert Minecraft sky angle to MakeUpUltraFast dayMoment
        // MC skyAngle: 0=noon, 0.25=sunset, 0.5=midnight, 0.75=sunrise
        // MakeUp dayMoment (worldTime/24000): 0=sunrise, 0.25=noon, 0.5=sunset, 0.75=midnight
        // Mapping: dayMoment = (skyAngle + 0.25) % 1.0
        float dayMoment = (skyAngle + 0.25f) % 1.0f;
        VRenderSystem.skyDayMoment = dayMoment;

        // dayMixer: smooth blend from sunset to day
        // f(x) = clamp(-((x - 0.25)^2) * 20 + 1.25, 0, 1)
        float dayDiff = dayMoment - 0.25f;
        VRenderSystem.skyDayMixer = Math.max(0.0f, Math.min(1.0f, -(dayDiff * dayDiff) * 20.0f + 1.25f));

        // nightMixer: smooth blend from sunset to night
        // g(x) = clamp(-((x - 0.75)^2) * 50 + 3.125, 0, 1)
        float nightDiff = dayMoment - 0.75f;
        VRenderSystem.skyNightMixer = Math.max(0.0f, Math.min(1.0f, -(nightDiff * nightDiff) * 50.0f + 3.125f));

        // Rain strength
        VRenderSystem.skyRainStrength = level.getRainLevel(partialTick);

        // Moon phase (0-7)
        VRenderSystem.skyMoonPhase = level.getMoonPhase();

        // Star brightness: visible at night, fading during transitions
        // Based on sun angle: stars visible when sun is below horizon
        float sunAngle;
        if (skyAngle < 0.75f) {
            sunAngle = skyAngle + 0.25f;
        } else {
            sunAngle = skyAngle - 0.75f;
        }

        // Stars visible roughly when sunAngle is 0.5-1.0 (night portion)
        float starBrightness;
        if (sunAngle > 0.5f) {
            // Night - stars visible
            float nightProgress = (sunAngle - 0.5f) * 2.0f; // 0 at sunset, 1 at midnight
            starBrightness = (float) Math.sin(nightProgress * Math.PI); // Peak at midnight
        } else {
            starBrightness = 0.0f;
        }
        // Reduce stars in rain
        starBrightness *= (1.0f - VRenderSystem.skyRainStrength);
        VRenderSystem.skyStarBrightness = starBrightness;

        // Shadow matrix computation follows Iris/Oculus shadow camera setup.
        float shadowSunAngle = skyAngle < 0.75f ? skyAngle + 0.25f : skyAngle - 0.75f;
        boolean isDay = shadowSunAngle <= 0.5f;
        float shadowAngle = isDay ? shadowSunAngle : shadowSunAngle - 0.5f;
        float shadowSkyAngle = shadowAngle < 0.25f ? shadowAngle + 0.75f : shadowAngle - 0.25f;
        VRenderSystem.shadowDayNightMix = isDay ? 1.0f : 0.0f;

        Matrix4f shadowMV = new Matrix4f()
            .identity()
            .translate(0.0f, 0.0f, SHADOW_BASE_TRANSLATE_Z)
            .rotate(Axis.XP.rotationDegrees(90.0f))
            .rotate(Axis.ZP.rotationDegrees(-shadowSkyAngle * 360.0f))
            .rotate(Axis.XP.rotationDegrees(SUN_PATH_ROTATION_DEGREES));

        float snapX = ((float) cameraPosition.x % SHADOW_INTERVAL_SIZE) - (SHADOW_INTERVAL_SIZE * 0.5f);
        float snapY = ((float) cameraPosition.y % SHADOW_INTERVAL_SIZE) - (SHADOW_INTERVAL_SIZE * 0.5f);
        float snapZ = ((float) cameraPosition.z % SHADOW_INTERVAL_SIZE) - (SHADOW_INTERVAL_SIZE * 0.5f);
        shadowMV.translate(snapX, snapY, snapZ);

        shadowMV.get(VRenderSystem.shadowModelView.buffer.asFloatBuffer());

        // Shadow projection: MakeUp/Oculus orthographic shadow camera with the
        // tuned near/far range from the original handoff plan.
        float halfDist = SHADOW_DISTANCE;
        Matrix4f shadowProj = new Matrix4f()
            .ortho(-halfDist, halfDist, -halfDist, halfDist, SHADOW_NEAR, SHADOW_FAR, true);
        shadowProj.get(VRenderSystem.shadowProjection.buffer.asFloatBuffer());

        // Shadow light position: direction of the shadow-casting celestial body in world space.
        // During the day this is the sun direction; at night it's the moon (anti-sun).
        if (!isDay) {
            VRenderSystem.shadowLightPosition.putFloat(0, -sunPosition.x);
            VRenderSystem.shadowLightPosition.putFloat(4, -sunPosition.y);
            VRenderSystem.shadowLightPosition.putFloat(8, -sunPosition.z);
        } else {
            VRenderSystem.shadowLightPosition.putFloat(0, sunPosition.x);
            VRenderSystem.shadowLightPosition.putFloat(4, sunPosition.y);
            VRenderSystem.shadowLightPosition.putFloat(8, sunPosition.z);
        }
    }

    private static double projectedNearPlaneHalfExtent(float projectionScale, double nearPlaneDistance) {
        if (Math.abs(projectionScale) <= 1.0e-6f) {
            return -1.0;
        }
        return Math.abs(nearPlaneDistance / projectionScale);
    }
}
