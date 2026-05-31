package net.vulkanmod.vulkshade.fallback;

import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.Renderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RendererBackend {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-RendererBackend");

    public enum BackendState {
        UNINITIALIZED,
        CHECKING_VULKAN,
        VULKAN_ACTIVE,
        OPENGL_FALLBACK,
        ERROR
    }

    public enum BackendType {
        VULKAN,
        OPENGL_FALLBACK
    }

    private BackendState state = BackendState.UNINITIALIZED;
    private boolean safeMode = false;

    private static RendererBackend INSTANCE;

    private RendererBackend() {
    }

    public static RendererBackend getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RendererBackend();
        }
        return INSTANCE;
    }

    public BackendState getState() {
        return state;
    }

    public boolean isReady() {
        return state == BackendState.VULKAN_ACTIVE || state == BackendState.OPENGL_FALLBACK;
    }

    public void transitionTo(BackendState newState) {
        switch (state) {
            case UNINITIALIZED:
                if (newState != BackendState.CHECKING_VULKAN && newState != BackendState.ERROR)
                    throw new IllegalStateException("Cannot transition from " + state + " to " + newState);
                break;
            case CHECKING_VULKAN:
                if (newState != BackendState.VULKAN_ACTIVE && newState != BackendState.OPENGL_FALLBACK && newState != BackendState.ERROR)
                    throw new IllegalStateException("Cannot transition from " + state + " to " + newState);
                break;
            case VULKAN_ACTIVE:
            case OPENGL_FALLBACK:
            case ERROR:
                if (newState == BackendState.UNINITIALIZED)
                    throw new IllegalStateException("Cannot transition from " + state + " to " + newState);
                break;
        }
        this.state = newState;
    }

    public boolean initializeBackend() {
        if (state == BackendState.VULKAN_ACTIVE) return true;
        if (state == BackendState.OPENGL_FALLBACK || state == BackendState.ERROR) return false;
        if (state == BackendState.CHECKING_VULKAN) {
            LOGGER.warn("initializeBackend called while CHECKING_VULKAN, re-entering");
        }

        transitionTo(BackendState.CHECKING_VULKAN);

        CompatibilityCheck check = CompatibilityCheck.getInstance();
        boolean compatible = check.checkVulkanSupport();

        if (!compatible) {
            LOGGER.warn("=== Vulkan compatibility check FAILED ===");
            for (String error : check.getErrors()) {
                LOGGER.warn("  [ERROR] {}", error);
            }
            for (String warning : check.getWarnings()) {
                LOGGER.warn("  [WARN] {}", warning);
            }
            LOGGER.warn("Falling back to OpenGL renderer");

            transitionTo(BackendState.OPENGL_FALLBACK);
            safeMode = true;
            return false;
        }

        if (check.hasWarnings()) {
            for (String warning : check.getWarnings()) {
                LOGGER.warn("  {}", warning);
            }
        }

        LOGGER.info("Vulkan compatible device detected: {} (driver {})",
            check.getDeviceName(), check.getDriverVersion());
        transitionTo(BackendState.VULKAN_ACTIVE);
        return true;
    }

    public void enterSafeMode() {
        this.safeMode = true;
        if (state == BackendState.VULKAN_ACTIVE) {
            LOGGER.warn("Entering Vulkan safe mode - reducing quality settings");
            net.vulkanmod.vulkshade.config.QualityPreset.LOW.apply();
        }
    }

    public boolean isSafeMode() {
        return safeMode;
    }

    public BackendType getCurrentBackend() {
        return switch (state) {
            case VULKAN_ACTIVE -> BackendType.VULKAN;
            case OPENGL_FALLBACK, ERROR -> BackendType.OPENGL_FALLBACK;
            case UNINITIALIZED, CHECKING_VULKAN -> BackendType.VULKAN;
        };
    }

    public boolean isVulkanActive() {
        return state == BackendState.VULKAN_ACTIVE && !safeMode;
    }

    public void shutdown() {
        if (state == BackendState.VULKAN_ACTIVE) {
            try {
                Renderer.getInstance().cleanUpResources();
            } catch (Exception e) {
                LOGGER.warn("Error during Vulkan shutdown", e);
            }
        }
        state = BackendState.UNINITIALIZED;
    }
}
