package net.vulkanmod.vulkshade.optimization;

import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkshade.config.VulkShadeConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PerformanceScaler {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-PerformanceScaler");
    private static PerformanceScaler INSTANCE;

    private static final long ADAPT_INTERVAL_MS = 2000;
    private static final int HISTORY_SIZE = 20;
    private static final float LOW_FPS_THRESHOLD = 40.0f;
    private static final float HIGH_FPS_THRESHOLD = 80.0f;
    private static final float STABLE_FPS_THRESHOLD = 55.0f;

    private final long[] frameTimeHistory = new long[HISTORY_SIZE];
    private int historyIndex = 0;
    private int historyCount = 0;

    private int currentRenderDistance;
    private int renderDistanceMin = 4;
    private int renderDistanceMax = 32;
    private int renderDistanceTarget;

    private float currentSSAOSamples;
    private float targetSSAOSamples;
    private int currentShadowRes;
    private int targetShadowRes;

    private long lastAdaptTime = 0;
    private boolean adaptiveMode = true;
    private boolean dynamicRenderDistance = true;
    private boolean framePacingEnabled = true;

    private long lastFrameTime = 0;
    private long targetFrameInterval = 0;
    private float smoothFPS = 60.0f;
    private float spikeThreshold = 1.5f;

    private PerformanceScaler() {
    }

    public static PerformanceScaler getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PerformanceScaler();
        }
        return INSTANCE;
    }

    public void initialize() {
        VulkShadeConfig cfg = VulkShadeConfig.getInstance();
        this.adaptiveMode = cfg.isAdaptivePerformance();
        this.dynamicRenderDistance = cfg.isDynamicRenderDistance();
        this.framePacingEnabled = cfg.isFramePacingEnabled();
        this.targetFrameInterval = cfg.getFramePacingTarget() > 0
            ? 1_000_000_000L / cfg.getFramePacingTarget() : 0;

        this.currentSSAOSamples = cfg.getSSAOSampleCount();
        this.targetSSAOSamples = this.currentSSAOSamples;
        this.currentShadowRes = cfg.getShadowResolution();
        this.targetShadowRes = this.currentShadowRes;

        LOGGER.info("PerformanceScaler initialized: adaptive={}, dynamicRD={}, framePacing={}",
            adaptiveMode, dynamicRenderDistance, framePacingEnabled);
    }

    public void syncWithGameOptions() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        this.currentRenderDistance = mc.options.renderDistance().get();
        this.renderDistanceTarget = this.currentRenderDistance;
        this.renderDistanceMax = Math.max(this.renderDistanceMax, this.currentRenderDistance);
        if (Initializer.CONFIG.voxyLODEnabled) {
            this.renderDistanceMin = Math.max(16, this.renderDistanceMin);
            this.renderDistanceTarget = Math.max(16, this.renderDistanceTarget);
        }
    }

    public void beginFrame(long currentTime) {
        long frameDelta = lastFrameTime > 0 ? currentTime - lastFrameTime : 0;
        lastFrameTime = currentTime;

        if (frameDelta > 0 && frameDelta < 1_000_000_000L) {
            frameTimeHistory[historyIndex] = frameDelta;
            historyIndex = (historyIndex + 1) % HISTORY_SIZE;
            if (historyCount < HISTORY_SIZE) historyCount++;

            float fps = 1_000_000_000.0f / frameDelta;
            smoothFPS = smoothFPS * 0.9f + fps * 0.1f;

            long now = System.currentTimeMillis();
            if (now - lastAdaptTime >= ADAPT_INTERVAL_MS) {
                lastAdaptTime = now;
                if (adaptiveMode) adaptQuality(smoothFPS);
                if (dynamicRenderDistance) adaptRenderDistance(smoothFPS);
            }
        }

        if (framePacingEnabled && targetFrameInterval > 0) {
            long elapsed = System.nanoTime() - currentTime;
            long remaining = targetFrameInterval - elapsed;
            if (remaining > 1_000_000L) {
                try {
                    Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void adaptQuality(float fps) {
        VulkShadeConfig cfg = VulkShadeConfig.getInstance();

        if (fps < LOW_FPS_THRESHOLD) {
            targetSSAOSamples = Math.max(8, (int) currentSSAOSamples - 8);
            targetShadowRes = Math.max(256, currentShadowRes / 2);
            int newRD = Math.max(renderDistanceMin, currentRenderDistance - 2);
            if (newRD != currentRenderDistance) {
                renderDistanceTarget = newRD;
                LOGGER.info("Adaptive: FPS={:.1f} < {} → reducing (SSAO={}, Shadow={}, RD={})",
                    fps, LOW_FPS_THRESHOLD, (int) targetSSAOSamples, targetShadowRes, newRD);
            }
        } else if (fps > HIGH_FPS_THRESHOLD && (targetSSAOSamples < currentSSAOSamples || targetShadowRes < currentShadowRes)) {
            targetSSAOSamples = Math.min(currentSSAOSamples, (int) targetSSAOSamples + 8);
            targetShadowRes = Math.min(currentShadowRes, targetShadowRes * 2);

            int newRD = Math.min(renderDistanceTarget + 1, renderDistanceMax);
            renderDistanceTarget = newRD;

            LOGGER.info("Adaptive: FPS={:.1f} > {} → increasing quality (SSAO={}, Shadow={}, RD={})",
                fps, HIGH_FPS_THRESHOLD, (int) targetSSAOSamples, targetShadowRes, newRD);
        } else if (fps > STABLE_FPS_THRESHOLD && targetSSAOSamples < currentSSAOSamples) {
            targetSSAOSamples = Math.min(currentSSAOSamples, (int) targetSSAOSamples + 4);
            targetShadowRes = Math.min(currentShadowRes, targetShadowRes * 2);
        }

        targetSSAOSamples = Math.max(8, Math.min(64, targetSSAOSamples));
        targetShadowRes = Math.max(256, Math.min(4096, targetShadowRes));

        cfg.setSSAOSampleCount((int) targetSSAOSamples);
        cfg.setShadowResolution(targetShadowRes);
    }

    private void adaptRenderDistance(float fps) {
        Minecraft mc = Minecraft.getInstance();
        int current = mc.options.renderDistance().get();

        if (fps < LOW_FPS_THRESHOLD && current > renderDistanceMin) {
            int newRD = Math.max(renderDistanceMin, current - 2);
            if (newRD != current) {
                mc.options.renderDistance().set(newRD);
                mc.levelRenderer.allChanged();
                LOGGER.info("Dynamic RD: reduced from {} to {} (FPS={:.1f})", current, newRD, fps);
            }
        } else if (fps > HIGH_FPS_THRESHOLD && current < renderDistanceTarget) {
            int newRD = Math.min(renderDistanceTarget, current + 2);
            if (newRD != current) {
                mc.options.renderDistance().set(newRD);
                mc.levelRenderer.allChanged();
                LOGGER.info("Dynamic RD: increased from {} to {} (FPS={:.1f})", current, newRD, fps);
            }
        }
        this.currentRenderDistance = mc.options.renderDistance().get();
    }

    public long getFrameInterval() { return targetFrameInterval; }
    public float getSmoothFPS() { return smoothFPS; }
    public int getCurrentRenderDistance() { return currentRenderDistance; }
    public int getRenderDistanceTarget() { return renderDistanceTarget; }

    public void setAdaptiveMode(boolean enabled) {
        this.adaptiveMode = enabled;
        LOGGER.info("Adaptive performance mode: {}", enabled ? "enabled" : "disabled");
    }

    public void setDynamicRenderDistance(boolean enabled) {
        this.dynamicRenderDistance = enabled;
        if (!enabled) {
            Minecraft mc = Minecraft.getInstance();
            mc.options.renderDistance().set(renderDistanceTarget);
            mc.levelRenderer.allChanged();
        }
        LOGGER.info("Dynamic render distance: {}", enabled ? "enabled" : "disabled");
    }

    public void setFramePacingEnabled(boolean enabled) {
        this.framePacingEnabled = enabled;
        LOGGER.info("Frame pacing: {}", enabled ? "enabled" : "disabled");
    }

    public void setTargetFPS(int fps) {
        this.targetFrameInterval = fps > 0 ? 1_000_000_000L / fps : 0;
        LOGGER.info("Frame pacing target: {} FPS ({})", fps,
            fps > 0 ? String.format("%.2f ms", 1000.0 / fps) : "unlimited");
    }

    public void setRenderDistanceLimits(int minRD, int maxRD) {
        this.renderDistanceMin = Math.max(2, minRD);
        this.renderDistanceMax = Math.max(this.renderDistanceMin, maxRD);
        this.renderDistanceTarget = Math.max(this.renderDistanceMin,
            Math.min(this.renderDistanceMax, this.renderDistanceTarget));
    }

    public boolean isSpikeDetected() {
        if (historyCount < 5) return false;
        long avg = 0;
        for (int i = 0; i < historyCount; i++) {
            avg += frameTimeHistory[i];
        }
        avg /= historyCount;
        long latest = frameTimeHistory[(historyIndex - 1 + HISTORY_SIZE) % HISTORY_SIZE];
        return latest > avg * spikeThreshold;
    }

    public void onQualityPresetChanged() {
        VulkShadeConfig cfg = VulkShadeConfig.getInstance();
        this.currentSSAOSamples = cfg.getSSAOSampleCount();
        this.targetSSAOSamples = this.currentSSAOSamples;
        this.currentShadowRes = cfg.getShadowResolution();
        this.targetShadowRes = this.currentShadowRes;

        Minecraft mc = Minecraft.getInstance();
        this.currentRenderDistance = mc.options.renderDistance().get();
        this.renderDistanceTarget = this.currentRenderDistance;
    }
}
