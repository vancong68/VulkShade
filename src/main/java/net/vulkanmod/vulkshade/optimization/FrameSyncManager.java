package net.vulkanmod.vulkshade.optimization;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

public class FrameSyncManager {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-FrameSync");
    private static final VkDevice DEVICE = Vulkan.getVkDevice();
    private static FrameSyncManager INSTANCE;

    private int frameCount;
    private int currentFrame;
    private FrameResources[] frames;
    private boolean timelineSemaphoresSupported = false;

    public FrameSyncManager() {
        this.frameCount = Math.max(3, Initializer.CONFIG.frameQueueSize);
        this.currentFrame = 0;
    }

    public static FrameSyncManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new FrameSyncManager();
        }
        return INSTANCE;
    }

    public void initialize() {
        checkTimelineSupport();
        frames = new FrameResources[frameCount];
        for (int i = 0; i < frameCount; i++) {
            frames[i] = new FrameResources(i);
            frames[i].create();
        }
        LOGGER.info("FrameSync initialized: {} frames in flight, timeline={}",
            frameCount, timelineSemaphoresSupported);
    }

    public void beginFrame() {
        FrameResources frame = frames[currentFrame];
        frame.waitForFence();

        try (MemoryStack stack = stackPush()) {
            if (timelineSemaphoresSupported) {
                frame.waitSemaphoreValue++;
                VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack);
                waitInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO);
                waitInfo.pSemaphores(stack.longs(frame.timelineSemaphore));
                waitInfo.pValues(stack.longs(frame.waitSemaphoreValue));
                vkWaitSemaphores(DEVICE, waitInfo, VUtil.UINT64_MAX);
            }
        }
    }

    public void endFrame() {
        FrameResources frame = frames[currentFrame];
        frame.signalFence();
        currentFrame = (currentFrame + 1) % frameCount;
    }

    public int getFrameCount() { return frameCount; }
    public int getCurrentFrame() { return currentFrame; }
    public FrameResources getCurrentFrameResources() { return frames[currentFrame]; }
    public VkCommandBuffer getCommandBuffer() { return frames[currentFrame].commandBuffer; }

    public long getImageAvailableSemaphore() {
        return frames[currentFrame].imageAvailableSemaphore;
    }

    public long getRenderFinishedSemaphore() {
        return frames[currentFrame].renderFinishedSemaphore;
    }

    public long getInFlightFence() {
        return frames[currentFrame].inFlightFence;
    }

    public void waitForAllFrames() {
        vkDeviceWaitIdle(DEVICE);
    }

    public void cleanup() {
        waitForAllFrames();
        for (FrameResources frame : frames) {
            if (frame != null) frame.destroy();
        }
    }

    public void recreate(int newFrameCount) {
        cleanup();
        this.frameCount = Math.max(3, newFrameCount);
        initialize();
    }

    private void checkTimelineSupport() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceTimelineSemaphoreFeatures timelineFeatures =
                VkPhysicalDeviceTimelineSemaphoreFeatures.calloc(stack);
            VkPhysicalDeviceFeatures2 features2 =
                VkPhysicalDeviceFeatures2.calloc(stack);
            features2.sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2);
            features2.pNext(timelineFeatures.address());

            VkPhysicalDevice physicalDevice = DeviceManager.physicalDevice;
            org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2(
                physicalDevice, features2);

            timelineSemaphoresSupported = timelineFeatures.timelineSemaphore();
        } catch (Exception e) {
            timelineSemaphoresSupported = false;
        }
    }

    public static class FrameResources {
        public final int index;
        public VkCommandBuffer commandBuffer;
        public long imageAvailableSemaphore;
        public long renderFinishedSemaphore;
        public long inFlightFence;
        public long timelineSemaphore;
        public long waitSemaphoreValue = 0;
        public long signalSemaphoreValue = 1;

        FrameResources(int index) {
            this.index = index;
        }

        void create() {
            try (MemoryStack stack = stackPush()) {
                commandBuffer = allocateCommandBuffer(stack);
                imageAvailableSemaphore = createSemaphore(stack);
                renderFinishedSemaphore = createSemaphore(stack);
                inFlightFence = createFence(stack, true);
            }
        }

        private VkCommandBuffer allocateCommandBuffer(MemoryStack stack) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(Vulkan.getCommandPool());
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(1);

            PointerBuffer pBuffer = stack.mallocPointer(1);
            if (vkAllocateCommandBuffers(DEVICE, allocInfo, pBuffer) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate frame command buffer");
            }
            return new VkCommandBuffer(pBuffer.get(0), DEVICE);
        }

        private long createSemaphore(MemoryStack stack) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            LongBuffer pSemaphore = stack.mallocLong(1);
            if (vkCreateSemaphore(DEVICE, semaphoreInfo, null, pSemaphore) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create semaphore");
            }
            return pSemaphore.get(0);
        }

        private long createFence(MemoryStack stack, boolean signaled) {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            if (signaled) {
                fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);
            }
            LongBuffer pFence = stack.mallocLong(1);
            if (vkCreateFence(DEVICE, fenceInfo, null, pFence) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create fence");
            }
            return pFence.get(0);
        }

        void waitForFence() {
            vkWaitForFences(DEVICE, inFlightFence, true, VUtil.UINT64_MAX);
            vkResetFences(DEVICE, inFlightFence);
        }

        void signalFence() {
            vkResetFences(DEVICE, inFlightFence);
        }

        void destroy() {
            vkDestroyFence(DEVICE, inFlightFence, null);
            vkDestroySemaphore(DEVICE, imageAvailableSemaphore, null);
            vkDestroySemaphore(DEVICE, renderFinishedSemaphore, null);
            vkFreeCommandBuffers(DEVICE, Vulkan.getCommandPool(), commandBuffer);
        }
    }
}
