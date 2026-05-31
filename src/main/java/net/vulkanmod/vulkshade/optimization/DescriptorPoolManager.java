package net.vulkanmod.vulkshade.optimization;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class DescriptorPoolManager {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-DescriptorPool");
    private static final VkDevice DEVICE = Vulkan.getVkDevice();
    private static DescriptorPoolManager INSTANCE;

    private final Map<Long, ReusablePool> poolCache = new HashMap<>();
    private final List<ReusablePool> allPools = new ArrayList<>();
    private int maxSetsPerPool = 256;
    private int poolCount = 0;
    private static final int VK_ERROR_OUT_OF_POOL_MEMORY = -1000069000;

    public DescriptorPoolManager() {
    }

    public static DescriptorPoolManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new DescriptorPoolManager();
        }
        return INSTANCE;
    }

    public long acquirePool(long descriptorSetLayout) {
        ReusablePool pool = poolCache.get(descriptorSetLayout);
        if (pool != null && pool.hasCapacity()) {
            pool.retain();
            return pool.pool;
        }

        ReusablePool newPool = createPool(descriptorSetLayout);
        poolCache.put(descriptorSetLayout, newPool);
        allPools.add(newPool);
        newPool.retain();
        return newPool.pool;
    }

    public void releasePool(long poolHandle) {
        ReusablePool pool = findPool(poolHandle);
        if (pool != null) {
            pool.release();
        }
    }

    public void releaseAllForFrame(int frameIndex) {
        for (ReusablePool pool : allPools) {
            if (pool.lastUsedFrame == frameIndex) {
                pool.reset();
            }
        }
    }

    public void resetAllPools() {
        for (ReusablePool pool : allPools) {
            vkResetDescriptorPool(DEVICE, pool.pool, 0);
            pool.usedCount = 0;
        }
    }

    public long allocateDescriptorSet(long poolHandle, long descriptorSetLayout) {
        ReusablePool pool = findPool(poolHandle);
        if (pool == null) return VK_NULL_HANDLE;

        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(pool.pool);
            allocInfo.pSetLayouts(stack.longs(descriptorSetLayout));

            LongBuffer pSet = stack.mallocLong(1);
            int result = vkAllocateDescriptorSets(DEVICE, allocInfo, pSet);
            if (result == VK_ERROR_OUT_OF_POOL_MEMORY) {
                ReusablePool newPool = growPool(descriptorSetLayout, pool);
                poolCache.put(descriptorSetLayout, newPool);
                allPools.add(newPool);
                return allocateDescriptorSet(newPool.pool, descriptorSetLayout);
            }
            if (result != VK_SUCCESS) {
                LOGGER.warn("Failed to allocate descriptor set: {}", result);
                return VK_NULL_HANDLE;
            }

            pool.usedCount++;
            return pSet.get(0);
        }
    }

    public void cleanup() {
        for (ReusablePool pool : allPools) {
            vkDestroyDescriptorPool(DEVICE, pool.pool, null);
        }
        poolCache.clear();
        allPools.clear();
        poolCount = 0;
    }

    public void setMaxSetsPerPool(int count) { this.maxSetsPerPool = Math.max(32, count); }
    public int getPoolCount() { return allPools.size(); }
    public long getTotalCapacity() {
        return allPools.stream().mapToLong(p -> p.capacity).sum();
    }

    private ReusablePool createPool(long descriptorSetLayout) {
        try (MemoryStack stack = stackPush()) {
            int poolSize = 4;
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(poolSize, stack);

            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(maxSetsPerPool * 2);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(maxSetsPerPool * 4);
            poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(maxSetsPerPool);
            poolSizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(maxSetsPerPool);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(maxSetsPerPool);
            poolInfo.flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT);

            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(DEVICE, poolInfo, null, pPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create descriptor pool");
            }

            ReusablePool pool = new ReusablePool(pPool.get(0), maxSetsPerPool);
            pool.id = poolCount++;
            LOGGER.debug("Created descriptor pool #{}: maxSets={}", pool.id, maxSetsPerPool);
            return pool;
        }
    }

    private ReusablePool growPool(long layout, ReusablePool oldPool) {
        int newCapacity = oldPool.capacity * 2;
        if (newCapacity > 4096) newCapacity = 4096;
        maxSetsPerPool = Math.max(maxSetsPerPool, newCapacity);
        return createPool(layout);
    }

    private ReusablePool findPool(long handle) {
        for (ReusablePool pool : allPools) {
            if (pool.pool == handle) return pool;
        }
        return null;
    }

    public static class ReusablePool {
        final long pool;
        int capacity;
        int usedCount;
        int refCount;
        int id;
        int lastUsedFrame;

        ReusablePool(long pool, int capacity) {
            this.pool = pool;
            this.capacity = capacity;
            this.usedCount = 0;
            this.refCount = 0;
        }

        boolean hasCapacity() { return usedCount < capacity; }

        void retain() { refCount++; lastUsedFrame = 0; }

        void release() {
            refCount--;
            if (refCount <= 0) {
                vkResetDescriptorPool(DEVICE, pool, 0);
                usedCount = 0;
                refCount = 0;
            }
        }

        void reset() {
            vkResetDescriptorPool(DEVICE, pool, 0);
            usedCount = 0;
        }
    }
}
