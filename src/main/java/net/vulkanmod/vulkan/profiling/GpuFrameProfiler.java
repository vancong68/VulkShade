package net.vulkanmod.vulkan.profiling;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.util.VkResult;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.util.Arrays;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_NOT_READY;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCmdResetQueryPool;
import static org.lwjgl.vulkan.VK10.vkCmdWriteTimestamp;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK10.vkGetQueryPoolResults;

public final class GpuFrameProfiler {
    public enum Stage {
        FRAME("frame"),
        SKY("skyPass"),
        SHADOW("shadowPass"),
        SHADOW_ENTITY("shadowEntity"),
        OPAQUE_TERRAIN("opaqueTerrain"),
        TRANSLUCENT_TERRAIN("translucentTerrain"),
        WATER_SCENE_COPY("waterSceneCopy"),
        POST_SCENE_COPY("postSceneCopy"),
        FSR_EASU("fsrEasu"),
        FSR_RCAS("fsrRcas"),
        VOLUMETRIC_POST("volumetricPost"),
        UNDERWATER_POST("underwaterPost");

        private final String displayName;

        Stage(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return this.displayName;
        }
    }

    public record Snapshot(long frameCount, double[] averagesMs) {
        public double averageMs(Stage stage) {
            return this.averagesMs[stage.ordinal()];
        }
    }

    private static final int QUERIES_PER_STAGE = 2;
    private static final int QUERY_STRIDE_BYTES = Long.BYTES * 2;
    private static final int QUERY_RESULT_FLAGS = VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT;

    private final long queryPool;
    private final int framesInFlight;
    private final float timestampPeriodNs;
    private final boolean[] pendingFrames;
    private final long[] accumulatedTicks;
    private final long[] queryPairReadback;

    private int activeFrame = -1;
    private long accumulatedFrameCount;

    private GpuFrameProfiler(long queryPool, int framesInFlight, float timestampPeriodNs) {
        this.queryPool = queryPool;
        this.framesInFlight = framesInFlight;
        this.timestampPeriodNs = timestampPeriodNs;
        this.pendingFrames = new boolean[framesInFlight];
        this.accumulatedTicks = new long[Stage.values().length];
        this.queryPairReadback = new long[QUERIES_PER_STAGE * 2];
    }

    public static GpuFrameProfiler create(int framesInFlight) {
        if (!supportsGraphicsTimestamps()) {
            Initializer.LOGGER.info("GPU timestamp profiling is unavailable on this device/queue");
            return null;
        }

        try (MemoryStack stack = stackPush()) {
            VkQueryPoolCreateInfo createInfo = VkQueryPoolCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO);
            createInfo.queryType(VK_QUERY_TYPE_TIMESTAMP);
            createInfo.queryCount(framesInFlight * Stage.values().length * QUERIES_PER_STAGE);

            long[] pQueryPool = new long[1];
            int result = vkCreateQueryPool(DeviceManager.vkDevice, createInfo, null, pQueryPool);
            if (result != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create GPU timestamp query pool: " + VkResult.decode(result));
            }

            return new GpuFrameProfiler(pQueryPool[0], framesInFlight, DeviceManager.deviceProperties.limits().timestampPeriod());
        }
    }

    public void cleanup() {
        vkDestroyQueryPool(DeviceManager.vkDevice, this.queryPool, null);
    }

    public void collectFrame(int frameIndex) {
        if (!this.pendingFrames[frameIndex]) {
            return;
        }

        long frameTicks = queryDelta(frameIndex, Stage.FRAME);
        if (frameTicks < 0L) {
            return;
        }

        this.pendingFrames[frameIndex] = false;

        for (Stage stage : Stage.values()) {
            long delta = stage == Stage.FRAME ? frameTicks : Math.max(0L, queryDelta(frameIndex, stage));
            if (delta > 0L) {
                this.accumulatedTicks[stage.ordinal()] += delta;
            }
        }

        this.accumulatedFrameCount++;
    }

    public void beginFrame(VkCommandBuffer commandBuffer, int frameIndex) {
        this.activeFrame = frameIndex;
        int firstQuery = baseQuery(frameIndex);
        vkCmdResetQueryPool(commandBuffer, this.queryPool, firstQuery, queryCountPerFrame());
        writeTimestamp(commandBuffer, Stage.FRAME, false);
    }

    public void endFrame(VkCommandBuffer commandBuffer) {
        if (this.activeFrame < 0) {
            return;
        }

        writeTimestamp(commandBuffer, Stage.FRAME, true);
        this.pendingFrames[this.activeFrame] = true;
        this.activeFrame = -1;
    }

    public void beginStage(Stage stage) {
        if (this.activeFrame < 0) {
            return;
        }

        writeTimestamp(Renderer.getCommandBuffer(), stage, false);
    }

    public void endStage(Stage stage) {
        if (this.activeFrame < 0) {
            return;
        }

        writeTimestamp(Renderer.getCommandBuffer(), stage, true);
    }

    public Snapshot snapshotAndReset() {
        if (this.accumulatedFrameCount <= 0L) {
            return null;
        }

        double scale = this.timestampPeriodNs / 1_000_000.0 / this.accumulatedFrameCount;
        double[] averagesMs = new double[Stage.values().length];
        for (Stage stage : Stage.values()) {
            averagesMs[stage.ordinal()] = this.accumulatedTicks[stage.ordinal()] * scale;
        }

        Snapshot snapshot = new Snapshot(this.accumulatedFrameCount, averagesMs);
        Arrays.fill(this.accumulatedTicks, 0L);
        this.accumulatedFrameCount = 0L;
        return snapshot;
    }

    private void writeTimestamp(VkCommandBuffer commandBuffer, Stage stage, boolean end) {
        int stageMask = end ? VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        vkCmdWriteTimestamp(commandBuffer, stageMask, this.queryPool, absoluteQueryIndex(this.activeFrame, stage, end));
    }

    private long queryDelta(int frameIndex, Stage stage) {
        Arrays.fill(this.queryPairReadback, 0L);
        int result = vkGetQueryPoolResults(
                DeviceManager.vkDevice,
                this.queryPool,
                absoluteQueryIndex(frameIndex, stage, false),
                QUERIES_PER_STAGE,
                this.queryPairReadback,
                QUERY_STRIDE_BYTES,
                QUERY_RESULT_FLAGS
        );
        if (result == VK_NOT_READY) {
            return -1L;
        }
        if (result != VK10.VK_SUCCESS) {
            throw new RuntimeException("Failed to read GPU timestamp queries: " + VkResult.decode(result));
        }
        if (this.queryPairReadback[1] == 0L || this.queryPairReadback[3] == 0L) {
            return -1L;
        }

        return Math.max(0L, this.queryPairReadback[2] - this.queryPairReadback[0]);
    }

    private int absoluteQueryIndex(int frameIndex, Stage stage, boolean end) {
        return baseQuery(frameIndex) + querySlot(stage, end);
    }

    private int querySlot(Stage stage, boolean end) {
        return (stage.ordinal() * QUERIES_PER_STAGE) + (end ? 1 : 0);
    }

    private int baseQuery(int frameIndex) {
        return frameIndex * queryCountPerFrame();
    }

    private int queryCountPerFrame() {
        return Stage.values().length * QUERIES_PER_STAGE;
    }

    private static boolean supportsGraphicsTimestamps() {
        int graphicsFamily = Queue.getQueueFamilies().graphicsFamily;
        if (graphicsFamily < 0) {
            return false;
        }

        try (MemoryStack stack = stackPush()) {
            IntBuffer queueFamilyCount = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(DeviceManager.physicalDevice, queueFamilyCount, null);

            VkQueueFamilyProperties.Buffer families =
                    VkQueueFamilyProperties.malloc(queueFamilyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(DeviceManager.physicalDevice, queueFamilyCount, families);

            if (graphicsFamily >= families.capacity()) {
                return false;
            }

            return families.get(graphicsFamily).timestampValidBits() > 0;
        }
    }
}
