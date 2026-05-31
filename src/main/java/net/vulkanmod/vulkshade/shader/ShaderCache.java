package net.vulkanmod.vulkshade.shader;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.SPIRVUtils.SPIRV;
import net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ShaderCache {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-ShaderCache");
    private static final Path CACHE_DIR = Path.of("vulkshade_cache", "shaders");
    private static final ShaderCache INSTANCE = new ShaderCache();

    private final Map<String, SPIRV> memoryCache = new ConcurrentHashMap<>();
    private final Map<String, Long> timestampCache = new ConcurrentHashMap<>();

    private ShaderCache() {
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            LOGGER.warn("Could not create shader cache directory", e);
        }
    }

    public static ShaderCache getInstance() {
        return INSTANCE;
    }

    public SPIRV getOrCompile(String name, String source, ShaderKind kind) {
        String cacheKey = cacheKey(name, kind);
        long sourceHash = hashSource(source);

        Long cachedTime = timestampCache.get(cacheKey);
        if (cachedTime != null && cachedTime == sourceHash) {
            SPIRV cached = memoryCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        Path diskPath = diskPath(cacheKey, sourceHash);
        SPIRV fromDisk = loadFromDisk(diskPath);
        if (fromDisk != null) {
            memoryCache.put(cacheKey, fromDisk);
            timestampCache.put(cacheKey, sourceHash);
            return fromDisk;
        }

        SPIRV compiled = SPIRVUtils.compileShader(name, source, kind);
        memoryCache.put(cacheKey, compiled);
        timestampCache.put(cacheKey, sourceHash);
        saveToDisk(diskPath, compiled.bytecode());
        return compiled;
    }

    public void invalidate(String name, ShaderKind kind) {
        String cacheKey = cacheKey(name, kind);
        memoryCache.remove(cacheKey);
        timestampCache.remove(cacheKey);
    }

    public void clear() {
        memoryCache.clear();
        timestampCache.clear();
    }

    private static String cacheKey(String name, ShaderKind kind) {
        return name + ":" + kind.name();
    }

    private static Path diskPath(String cacheKey, long hash) {
        String safeName = cacheKey.replaceAll("[^a-zA-Z0-9_:]", "_");
        return CACHE_DIR.resolve(safeName + "_" + Long.toHexString(hash) + ".spv");
    }

    private static long hashSource(String source) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(source.getBytes());
            ByteBuffer bb = ByteBuffer.wrap(digest);
            return bb.getLong(0) ^ bb.getLong(8);
        } catch (NoSuchAlgorithmException e) {
            return source.hashCode();
        }
    }

    private static SPIRV loadFromDisk(Path path) {
        if (!Files.exists(path)) return null;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate((int) channel.size());
            channel.read(buf);
            buf.flip();
            return new SPIRV(0, buf);
        } catch (IOException e) {
            return null;
        }
    }

    private static void saveToDisk(Path path, ByteBuffer bytecode) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int pos = bytecode.position();
            bytecode.position(0);
            channel.write(bytecode);
            bytecode.position(pos);
        } catch (IOException e) {
            LOGGER.warn("Failed to cache shader to disk: {}", path, e);
        }
    }
}
