package net.vulkanmod.vulkshade.optimization;

import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.SPIRVUtils.SPIRV;
import net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind;
import net.vulkanmod.vulkshade.shader.FallbackShader;
import net.vulkanmod.vulkshade.shader.ShaderCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class AsyncShaderCompiler {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-AsyncShader");
    private static AsyncShaderCompiler INSTANCE;

    private final ExecutorService compilerPool;
    private final Queue<CompileJob> pendingJobs = new ConcurrentLinkedQueue<>();
    private final List<CompileJob> completedJobs = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean running = true;

    private AsyncShaderCompiler() {
        int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        this.compilerPool = Executors.newFixedThreadPool(
            threadCount,
            r -> {
                Thread t = new Thread(r, "VulkShade-ShaderCompiler");
                t.setDaemon(true);
                return t;
            }
        );
        LOGGER.info("AsyncShaderCompiler initialized with {} worker threads", threadCount);
    }

    public static AsyncShaderCompiler getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AsyncShaderCompiler();
        }
        return INSTANCE;
    }

    public CompileJob submitCompile(String name, String source, ShaderKind kind, Consumer<SPIRV> callback) {
        CompileJob job = new CompileJob(name, source, kind, callback);
        pendingJobs.add(job);

        compilerPool.submit(() -> {
            try {
                SPIRV result = ShaderCache.getInstance().getOrCompile(name, source, kind);
                job.result = result;
                job.success = true;
            } catch (Exception e) {
                LOGGER.error("Async compile failed for '{}': {}", name, e.getMessage());
                job.result = FallbackShader.getInstance().getOrCreateFallback(kind);
                job.success = false;
                job.error = e;
            }
            completedJobs.add(job);
        });

        return job;
    }

    public void pollCompleted() {
        List<CompileJob> ready;
        synchronized (completedJobs) {
            ready = new ArrayList<>(completedJobs);
            completedJobs.clear();
        }

        for (CompileJob job : ready) {
            if (job.callback != null) {
                try {
                    job.callback.accept(job.result);
                } catch (Exception e) {
                    LOGGER.error("Error in shader compile callback for '{}'", job.name, e);
                }
            }
            job.done = true;
        }
    }

    public void waitForAll() {
        while (!pendingJobs.isEmpty() || !completedJobs.isEmpty()) {
            pollCompleted();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void shutdown() {
        running = false;
        compilerPool.shutdown();
        try {
            compilerPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOGGER.info("AsyncShaderCompiler shut down");
    }

    public int getPendingCount() { return pendingJobs.size(); }
    public int getCompletedCount() { return completedJobs.size(); }
    public boolean isRunning() { return running; }

    public static class CompileJob {
        final String name;
        final String source;
        final ShaderKind kind;
        final Consumer<SPIRV> callback;
        volatile SPIRV result;
        volatile boolean success;
        volatile boolean done;
        volatile Throwable error;

        CompileJob(String name, String source, ShaderKind kind, Consumer<SPIRV> callback) {
            this.name = name;
            this.source = source;
            this.kind = kind;
            this.callback = callback;
        }

        public boolean isDone() { return done; }
        public boolean isSuccess() { return success; }
        public SPIRV getResult() { return result; }
        public Throwable getError() { return error; }
    }
}
