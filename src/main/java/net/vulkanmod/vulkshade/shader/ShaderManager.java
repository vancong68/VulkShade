package net.vulkanmod.vulkshade.shader;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.SPIRVUtils.SPIRV;
import net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ShaderManager {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-ShaderManager");
    private static final ShaderManager INSTANCE = new ShaderManager();

    private final Map<String, ShaderEntry> shaderRegistry = new ConcurrentHashMap<>();
    private final List<Pipeline> managedPipelines = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean hotReloadEnabled = true;

    private ShaderManager() {
    }

    public static ShaderManager getInstance() {
        return INSTANCE;
    }

    public void initialize(boolean enableHotReload) {
        this.hotReloadEnabled = enableHotReload;
        if (enableHotReload) {
            try {
                URL shaderUrl = ShaderManager.class.getResource("/assets/vulkanmod/shaders/");
                if (shaderUrl != null) {
                    Path shaderPath;
                    try {
                        shaderPath = Paths.get(shaderUrl.toURI());
                    } catch (URISyntaxException e) {
                        shaderPath = Paths.get(shaderUrl.getPath());
                    }
                    if (Files.isDirectory(shaderPath)) {
                        ShaderWatcher.getInstance().start(shaderPath);
                        ShaderWatcher.getInstance().onShaderChanged(this::onShaderFileChanged);
                        LOGGER.info("Shader hot-reload initialized");
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not initialize shader hot-reload", e);
            }
        }
    }

    public ShaderEntry registerShader(String name, String vertexSource, String fragmentSource) {
        ShaderEntry entry = new ShaderEntry(name);
        entry.setVertexSource(vertexSource);
        entry.setFragmentSource(fragmentSource);
        shaderRegistry.put(name, entry);
        return entry;
    }

    public ShaderEntry getShader(String name) {
        return shaderRegistry.get(name);
    }

    public boolean compileShader(String name) {
        ShaderEntry entry = shaderRegistry.get(name);
        if (entry == null) return false;
        return compileShaderEntry(entry);
    }

    public boolean compileAll() {
        boolean allSuccess = true;
        for (ShaderEntry entry : shaderRegistry.values()) {
            if (!compileShaderEntry(entry)) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    public void registerPipeline(Pipeline pipeline) {
        managedPipelines.add(pipeline);
    }

    public void unregisterPipeline(Pipeline pipeline) {
        managedPipelines.remove(pipeline);
    }

    public void reloadAll() {
        ShaderCache.getInstance().clear();
        for (ShaderEntry entry : shaderRegistry.values()) {
            entry.invalidateCache();
        }
        compileAll();
        LOGGER.info("All shaders reloaded");
    }

    public void shutdown() {
        ShaderWatcher.getInstance().stop();
        shaderRegistry.clear();
        managedPipelines.clear();
    }

    private boolean compileShaderEntry(ShaderEntry entry) {
        try {
            if (entry.vertexSource != null) {
                SPIRV vert = getCachedSpirv(entry.name + ".vsh", entry.vertexSource, ShaderKind.VERTEX_SHADER);
                entry.vertexSPIRV = vert;
            }
            if (entry.fragmentSource != null) {
                SPIRV frag = getCachedSpirv(entry.name + ".fsh", entry.fragmentSource, ShaderKind.FRAGMENT_SHADER);
                entry.fragmentSPIRV = frag;
            }
            if (entry.computeSource != null) {
                SPIRV comp = getCachedSpirv(entry.name + ".comp", entry.computeSource, ShaderKind.COMPUTE_SHADER);
                entry.computeSPIRV = comp;
            }
            entry.compiled = true;
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to compile shader '{}': {}", entry.name, e.getMessage());
            entry.compiled = false;
            installFallback(entry);
            return false;
        }
    }

    private SPIRV getCachedSpirv(String name, String source, ShaderKind kind) {
        return ShaderCache.getInstance().getOrCompile(name, source, kind);
    }

    private void installFallback(ShaderEntry entry) {
        if (entry.vertexSPIRV == null && entry.vertexSource != null) {
            entry.vertexSPIRV = FallbackShader.getInstance().getOrCreateFallback(ShaderKind.VERTEX_SHADER);
        }
        if (entry.fragmentSPIRV == null && entry.fragmentSource != null) {
            entry.fragmentSPIRV = FallbackShader.getInstance().getOrCreateFallback(ShaderKind.FRAGMENT_SHADER);
        }
        if (entry.computeSPIRV == null && entry.computeSource != null) {
            entry.computeSPIRV = FallbackShader.getInstance().getOrCreateFallback(ShaderKind.COMPUTE_SHADER);
        }
    }

    private void onShaderFileChanged(String fileName) {
        if (!hotReloadEnabled) return;
        LOGGER.info("Shader file changed: {}, triggering reload", fileName);
        reloadAll();
    }

    public static class ShaderEntry {
        private final String name;
        private String vertexSource;
        private String fragmentSource;
        private String computeSource;
        private SPIRV vertexSPIRV;
        private SPIRV fragmentSPIRV;
        private SPIRV computeSPIRV;
        private boolean compiled;

        ShaderEntry(String name) {
            this.name = name;
        }

        public void setVertexSource(String source) { this.vertexSource = source; }
        public void setFragmentSource(String source) { this.fragmentSource = source; }
        public void setComputeSource(String source) { this.computeSource = source; }

        public SPIRV getVertexSPIRV() { return vertexSPIRV; }
        public SPIRV getFragmentSPIRV() { return fragmentSPIRV; }
        public SPIRV getComputeSPIRV() { return computeSPIRV; }
        public boolean isCompiled() { return compiled; }
        public String getName() { return name; }

        void invalidateCache() {
            ShaderCache.getInstance().invalidate(name + ".vsh", ShaderKind.VERTEX_SHADER);
            ShaderCache.getInstance().invalidate(name + ".fsh", ShaderKind.FRAGMENT_SHADER);
            if (computeSource != null) {
                ShaderCache.getInstance().invalidate(name + ".comp", ShaderKind.COMPUTE_SHADER);
            }
        }
    }
}
