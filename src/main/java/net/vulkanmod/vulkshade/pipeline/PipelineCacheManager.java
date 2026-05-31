package net.vulkanmod.vulkshade.pipeline;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class PipelineCacheManager {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-PipelineCache");
    private static final Path CACHE_DIR = Path.of("vulkshade_cache");
    private static final int CACHE_HEADER_SIZE = 16;
    private static final int VK_UUID_SIZE = 16;
    private static final int VK_PIPELINE_CACHE_HEADER_SIZE = 32;

    private static Path cachePath;
    private static long pipelineCache = VK_NULL_HANDLE;
    private static boolean initialized = false;
    private static String cacheKey;

    public static void initialize() {
        if (initialized) return;

        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            LOGGER.warn("Could not create pipeline cache directory", e);
        }

        cacheKey = buildCacheKey();
        cachePath = CACHE_DIR.resolve("pipeline_cache_" + cacheKey + ".bin");
        LOGGER.debug("Pipeline cache key: {} (path: {})", cacheKey, cachePath);

        ByteBuffer initialData = validateAndLoad();

        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack);
            cacheInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);
            if (initialData != null) {
                cacheInfo.pInitialData(initialData);
            }

            java.nio.LongBuffer pCache = stack.mallocLong(1);
            int result = vkCreatePipelineCache(Vulkan.getVkDevice(), cacheInfo, null, pCache);
            if (result != VK_SUCCESS) {
                LOGGER.warn("Failed to create pipeline cache with data, retrying empty");
                cacheInfo.pInitialData(null);
                result = vkCreatePipelineCache(Vulkan.getVkDevice(), cacheInfo, null, pCache);
                if (result != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create pipeline cache");
                }
            }
            pipelineCache = pCache.get(0);
        }

        initialized = true;
        LOGGER.info("Pipeline cache initialized: {}", cachePath.getFileName());
    }

    public static long getPipelineCache() {
        if (!initialized) initialize();
        return pipelineCache;
    }

    public static void saveToDisk() {
        if (pipelineCache == VK_NULL_HANDLE) return;

        try (MemoryStack stack = stackPush()) {
            PointerBuffer pDataSize = stack.mallocPointer(1);
            int result = vkGetPipelineCacheData(Vulkan.getVkDevice(), pipelineCache,
                pDataSize, null);
            if (result != VK_SUCCESS) return;

            long dataSize = pDataSize.get(0);
            if (dataSize <= 0) return;

            ByteBuffer data = ByteBuffer.allocateDirect((int) dataSize);
            result = vkGetPipelineCacheData(Vulkan.getVkDevice(), pipelineCache,
                pDataSize, data);
            if (result != VK_SUCCESS) return;

            Path tmpPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
            try (FileChannel channel = FileChannel.open(tmpPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                data.position(0);
                channel.write(data);
            } catch (IOException e) {
                LOGGER.warn("Failed to write temp pipeline cache", e);
                return;
            }

            try {
                Files.move(tmpPath, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                try {
                    Files.move(tmpPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e2) {
                    LOGGER.warn("Failed to finalize pipeline cache", e2);
                }
            }

            LOGGER.debug("Pipeline cache saved: {} bytes to {}", data.capacity(), cachePath.getFileName());
        }
    }

    public static void destroy() {
        saveToDisk();
        if (pipelineCache != VK_NULL_HANDLE) {
            vkDestroyPipelineCache(Vulkan.getVkDevice(), pipelineCache, null);
            pipelineCache = VK_NULL_HANDLE;
        }
        initialized = false;
    }

    public static void invalidateCache() {
        if (cachePath != null) {
            try {
                Files.deleteIfExists(cachePath);
                LOGGER.info("Pipeline cache invalidated: {}", cachePath.getFileName());
            } catch (IOException e) {
                LOGGER.warn("Failed to delete invalid cache", e);
            }
        }
        initialized = false;
    }

    private static String buildCacheKey() {
        try {
            var props = DeviceManager.deviceProperties;
            String vendorHex = Integer.toHexString(props.vendorID());
            String deviceHex = Integer.toHexString(props.deviceID());
            int major = VK_VERSION_MAJOR(props.driverVersion());
            int minor = VK_VERSION_MINOR(props.driverVersion());

            String raw = String.format("%s_%s_%d_%d", vendorHex, deviceHex, major, minor);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(raw.getBytes());
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException | NullPointerException e) {
            return "default";
        }
    }

    private static ByteBuffer validateAndLoad() {
        if (!Files.exists(cachePath)) return null;
        if (!Files.isReadable(cachePath)) return null;

        try (FileChannel channel = FileChannel.open(cachePath, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < VK_PIPELINE_CACHE_HEADER_SIZE) {
                LOGGER.warn("Pipeline cache too small ({} bytes), ignoring", fileSize);
                Files.delete(cachePath);
                return null;
            }

            ByteBuffer header = ByteBuffer.allocate(VK_PIPELINE_CACHE_HEADER_SIZE);
            channel.read(header);
            header.flip();

            int headerLength = header.getInt(0);
            int headerVersion = header.getInt(4);
            int vendorID = header.getInt(8);
            int deviceID = header.getInt(12);

            var props = DeviceManager.deviceProperties;
            if (vendorID != props.vendorID() || deviceID != props.deviceID()) {
                LOGGER.warn("Pipeline cache vendor/device mismatch, ignoring");
                LOGGER.debug("  Cached: vendor={:#x} device={:#x}", vendorID, deviceID);
                LOGGER.debug("  Current: vendor={:#x} device={:#x}", props.vendorID(), props.deviceID());
                Files.delete(cachePath);
                return null;
            }

            header.position(0);
            ByteBuffer fullData = ByteBuffer.allocateDirect((int) fileSize);
            fullData.put(header);
            channel.position(VK_PIPELINE_CACHE_HEADER_SIZE);
            int remaining = channel.read(fullData);
            if (remaining < 0) {
                Files.delete(cachePath);
                return null;
            }
            fullData.flip();
            return fullData;

        } catch (IOException e) {
            LOGGER.warn("Failed to read pipeline cache, will rebuild", e);
            try { Files.deleteIfExists(cachePath); } catch (IOException ignored) {}
            return null;
        }
    }
}
