package net.vulkanmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.UpdateChecker;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.render.chunk.build.frapi.VulkanModRenderer;
import net.vulkanmod.vulkshade.VulkShade;
import net.vulkanmod.vulkshade.fallback.CompatibilityCheck;
import net.vulkanmod.vulkshade.fallback.RendererBackend;
import net.vulkanmod.vulkshade.fallback.RendererBackend.BackendState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public class Initializer implements ClientModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("VulkShade");

	private static String VERSION;
	public static Config CONFIG;

	@Override
	public void onInitializeClient() {

		VERSION = FabricLoader.getInstance()
				.getModContainer("vulkanmod")
				.get()
				.getMetadata()
				.getVersion().getFriendlyString();

		LOGGER.info("== VulkShade ==");

		Platform.init();
		VideoModeManager.init();

		var configPath = FabricLoader.getInstance()
				.getConfigDir()
				.resolve("vulkshade_settings.json");

		CONFIG = loadConfig(configPath);

		RendererBackend backend = RendererBackend.getInstance();
		backend.transitionTo(BackendState.CHECKING_VULKAN);

		CompatibilityCheck check = CompatibilityCheck.getInstance();
		boolean vulkanCompatible = check.checkVulkanSupport();

		if (vulkanCompatible) {
			backend.transitionTo(BackendState.VULKAN_ACTIVE);
		} else {
			backend.transitionTo(BackendState.OPENGL_FALLBACK);
			LOGGER.warn("=== Vulkan not fully compatible, entering safe mode ===");
			for (String err : check.getErrors()) {
				LOGGER.warn("  [ERROR] {}", err);
			}
		}

		VulkShade.getInstance().initialize();

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			VulkShade.getInstance().onClientStarted();
		});

		Renderer.register(VulkanModRenderer.INSTANCE);

		UpdateChecker.checkForUpdates();
	}

	private static Config loadConfig(Path path) {
		Config config = Config.load(path);

		if(config == null) {
			config = new Config();
			config.write();
		}

		return config;
	}

	public static String getVersion() {
		return VERSION;
	}
}
