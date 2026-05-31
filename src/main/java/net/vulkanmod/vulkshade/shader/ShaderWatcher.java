package net.vulkanmod.vulkshade.shader;

import net.vulkanmod.Initializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static java.nio.file.StandardWatchEventKinds.*;

public class ShaderWatcher {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-ShaderWatcher");
    private static final ShaderWatcher INSTANCE = new ShaderWatcher();

    private final Map<Path, Consumer<String>> callbacks = new ConcurrentHashMap<>();
    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean running = false;

    private ShaderWatcher() {
    }

    public static ShaderWatcher getInstance() {
        return INSTANCE;
    }

    public void start(Path shaderDir) {
        if (running) return;
        if (!Files.isDirectory(shaderDir)) return;

        if (!shaderDir.getFileSystem().provider().getScheme().equals("file")) {
            LOGGER.info("Shader hot-reload unavailable: shader path is inside a JAR/zip (scheme={})",
                shaderDir.getFileSystem().provider().getScheme());
            return;
        }

        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            registerRecursive(shaderDir);

            this.running = true;
            this.watchThread = new Thread(this::pollLoop, "VulkShade-ShaderWatcher");
            this.watchThread.setDaemon(true);
            this.watchThread.start();

            LOGGER.info("Shader hot-reload enabled for: {}", shaderDir);
        } catch (IOException e) {
            LOGGER.warn("Could not start shader watcher", e);
        }
    }

    public void stop() {
        this.running = false;
        if (this.watchThread != null) {
            this.watchThread.interrupt();
        }
        if (this.watchService != null) {
            try {
                this.watchService.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void onShaderChanged(Consumer<String> callback) {
        Path dummy = Paths.get("dummy");
        callbacks.put(dummy, callback);
    }

    private void registerRecursive(Path dir) throws IOException {
        Files.walk(dir).filter(Files::isDirectory).forEach(d -> {
            if (!d.getFileSystem().provider().getScheme().equals("file")) return;
            try {
                d.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            } catch (IOException e) {
                LOGGER.warn("Could not watch directory: {}", d, e);
            }
        });
    }

    private void pollLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == OVERFLOW) continue;

                    Path changed = (Path) event.context();
                    String name = changed.toString();
                    if (name.endsWith(".vsh") || name.endsWith(".fsh") || name.endsWith(".glsl")) {
                        callbacks.values().forEach(c -> c.accept(name));
                    }
                }

                if (!key.reset()) {
                    running = false;
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
        }
    }
}
