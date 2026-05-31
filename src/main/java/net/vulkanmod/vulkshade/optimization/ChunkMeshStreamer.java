package net.vulkanmod.vulkshade.optimization;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChunkMeshStreamer {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-MeshStreamer");
    private static ChunkMeshStreamer INSTANCE;

    private static final int MAX_QUEUED_JOBS = 64;

    private final BlockingQueue<Runnable> uploadQueue = new ArrayBlockingQueue<>(MAX_QUEUED_JOBS);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread uploadThread;

    private long totalBytesUploaded = 0;
    private int uploadsThisFrame = 0;
    private long totalUploads = 0;

    public ChunkMeshStreamer() {
    }

    public static ChunkMeshStreamer getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ChunkMeshStreamer();
        }
        return INSTANCE;
    }

    public void initialize() {
        this.running.set(true);
        this.uploadThread = new Thread(this::uploadLoop, "VulkShade-MeshStreamer");
        this.uploadThread.setDaemon(true);
        this.uploadThread.start();
        LOGGER.info("ChunkMeshStreamer initialized");
    }

    public void enqueueUpload(long dstPtr, ByteBuffer src, int size) {
        if (!running.get()) return;
        Runnable task = () -> {
            MemoryUtil.memCopy(MemoryUtil.memAddress(src), dstPtr, size);
            totalBytesUploaded += size;
            totalUploads++;
        };
        if (!uploadQueue.offer(task)) {
            task.run();
        }
    }

    private void uploadLoop() {
        while (running.get()) {
            try {
                Runnable task = uploadQueue.take();
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void beginFrame() {
        uploadsThisFrame = 0;
    }

    public void endFrame() {
        Runnable task;
        while ((task = uploadQueue.poll()) != null) {
            task.run();
            uploadsThisFrame++;
        }
    }

    public void cleanup() {
        running.set(false);
        if (uploadThread != null) {
            uploadThread.interrupt();
            try {
                uploadThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        uploadQueue.clear();
        LOGGER.info("ChunkMeshStreamer shut down. {} uploads, {} MB total",
            totalUploads, totalBytesUploaded / (1024 * 1024));
    }

    public long getTotalBytesUploaded() { return totalBytesUploaded; }
    public long getTotalUploads() { return totalUploads; }
}
