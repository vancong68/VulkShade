package net.vulkanmod.vulkshade.optimization;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkshade.shader.ComputePipeline;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK10.*;

public class CullingSystemUpgrade {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-Culling");
    private static CullingSystemUpgrade INSTANCE;

    private final FrustumIntersection frustumIntersection = new FrustumIntersection();
    private Vector3f cameraPos = new Vector3f();
    private Matrix4f projectionMatrix = new Matrix4f();
    private Matrix4f modelViewMatrix = new Matrix4f();

    private int culledThisFrame = 0;
    private int visibleThisFrame = 0;

    private boolean occlusionCullingEnabled = false;
    private boolean hizEnabled = false;

    private final DistanceLODConfig lodConfig = new DistanceLODConfig();
    private final HiZBuffer hiZBuffer = new HiZBuffer();
    private final OcclusionQueryPool queryPool = new OcclusionQueryPool();

    public CullingSystemUpgrade() {
    }

    public static CullingSystemUpgrade getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CullingSystemUpgrade();
        }
        return INSTANCE;
    }

    public void initialize() {
        this.lodConfig.updateFromConfig();
        this.queryPool.initialize();

        LOGGER.info("CullingSystemUpgrade: occlusion={}, hiz={}, LOD enabled",
            occlusionCullingEnabled, hizEnabled);
    }

    public void beginFrame(Vector3f cameraPos, Matrix4f proj, Matrix4f view) {
        this.cameraPos.set(cameraPos);
        this.projectionMatrix.set(proj);
        this.modelViewMatrix.set(view);

        Matrix4f mvp = new Matrix4f(proj).mul(view);
        frustumIntersection.set(mvp);

        culledThisFrame = 0;
        visibleThisFrame = 0;

        if (hiZBuffer.isEnabled()) {
            hiZBuffer.beginFrame();
        }
        queryPool.beginFrame();
    }

    public boolean isAreaVisible(ChunkArea area) {
        float minX = area.getPosition().x();
        float minY = area.getPosition().y();
        float minZ = area.getPosition().z();
        float maxX = minX + (ChunkAreaManager.WIDTH << 4);
        float maxY = minY + (ChunkAreaManager.WIDTH << 4);
        float maxZ = minZ + (ChunkAreaManager.WIDTH << 4);

        if (!frustumIntersection.testAab(minX, minY, minZ, maxX, maxY, maxZ)) {
            culledThisFrame++;
            return false;
        }

        if (occlusionCullingEnabled) {
            float cx = (minX + maxX) * 0.5f;
            float cy = (minY + maxY) * 0.5f;
            float cz = (minZ + maxZ) * 0.5f;
            float halfSize = (maxX - minX) * 0.5f;

            if (hiZBuffer.isEnabled()) {
                boolean occluded = !hiZBuffer.testOcclusion(
                    cx - halfSize, cy - halfSize, cz - halfSize,
                    cx + halfSize, cy + halfSize, cz + halfSize,
                    projectionMatrix, modelViewMatrix
                );
                if (occluded) {
                    culledThisFrame++;
                    return false;
                }
            }
        }

        visibleThisFrame++;
        return true;
    }

    public boolean isSectionVisible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return frustumIntersection.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public float getLODDistanceSq(float sectionMinX, float sectionMinY, float sectionMinZ,
                                  float sectionMaxX, float sectionMaxY, float sectionMaxZ) {
        float cx = (sectionMinX + sectionMaxX) * 0.5f;
        float cy = (sectionMinY + sectionMaxY) * 0.5f;
        float cz = (sectionMinZ + sectionMaxZ) * 0.5f;

        float dx = cx - cameraPos.x;
        float dy = cy - cameraPos.y;
        float dz = cz - cameraPos.z;

        return dx * dx + dy * dy + dz * dz;
    }

    public int getLODLevel(float distanceSq) {
        float distance = (float) Math.sqrt(distanceSq);
        return lodConfig.getLODLevel(distance);
    }

    public int getCulledCount() { return culledThisFrame; }
    public int getVisibleCount() { return visibleThisFrame; }
    public float getCullRatio() {
        int total = culledThisFrame + visibleThisFrame;
        return total > 0 ? (float) culledThisFrame / total * 100f : 0f;
    }

    public void setOcclusionCullingEnabled(boolean enabled) {
        this.occlusionCullingEnabled = enabled;
        LOGGER.info("Occlusion culling {}", enabled ? "enabled" : "disabled");
    }

    public boolean isOcclusionCullingEnabled() { return occlusionCullingEnabled; }

    public void setHizEnabled(boolean enabled) {
        this.hizEnabled = enabled;
        this.hiZBuffer.setEnabled(enabled);
    }

    public boolean isHizEnabled() { return hizEnabled; }

    public DistanceLODConfig getLodConfig() { return lodConfig; }
    public HiZBuffer getHiZBuffer() { return hiZBuffer; }

    public void cleanup() {
        hiZBuffer.cleanup();
        queryPool.cleanup();
    }

    public static class DistanceLODConfig {
        public static final int LOD_NEAR = 0;
        public static final int LOD_MID = 1;
        public static final int LOD_FAR = 2;

        public boolean enabled = true;
        public float lod0Distance = 48.0f;
        public float lod1Distance = 96.0f;
        public float lod2Distance = 160.0f;
        public int maxLOD = 2;

        void updateFromConfig() {
            net.vulkanmod.vulkshade.config.VulkShadeConfig cfg =
                net.vulkanmod.vulkshade.config.VulkShadeConfig.getInstance();
            this.enabled = cfg.isLodCulling();
        }

        public int getLODLevel(float distance) {
            if (!enabled) return 0;
            if (distance < lod0Distance) return 0;
            if (distance < lod1Distance) return 1;
            if (distance < lod2Distance) return 2;
            return maxLOD;
        }

        public float getLODDistance(int level) {
            return switch (level) {
                case 0 -> lod0Distance;
                case 1 -> lod1Distance;
                case 2 -> lod2Distance;
                default -> Float.MAX_VALUE;
            };
        }
    }

    public static class HiZBuffer {
        private boolean enabled = false;
        private VulkanImage depthImage;
        private VulkanImage[] mipImages;
        private int width;
        private int height;
        private int mipLevels;
        private ComputePipeline hizReducePipeline;

        public void initialize(int fbWidth, int fbHeight) {
            if (!enabled) return;
            this.width = fbWidth;
            this.height = fbHeight;
            this.mipLevels = (int) (Math.floor(Math.log(Math.max(fbWidth, fbHeight)) / Math.log(2)));

            mipImages = new VulkanImage[mipLevels];
            hizReducePipeline = new ComputePipeline("hiz_reduce");
            hizReducePipeline.compileFromSource(generateHiZSource());
            hizReducePipeline.create();

            LOGGER.debug("Hi-Z buffer initialized: {}x{} with {} mips", fbWidth, fbHeight, mipLevels);
        }

        public void beginFrame() {
        }

        public void reduce(VkCommandBuffer cmdBuffer) {
            if (!enabled || hizReducePipeline == null) return;

            for (int i = 1; i < mipLevels; i++) {
                int srcWidth = Math.max(1, width >> (i - 1));
                int srcHeight = Math.max(1, height >> (i - 1));
                int dstWidth = Math.max(1, width >> i);
                int dstHeight = Math.max(1, height >> i);

                hizReducePipeline.dispatch(cmdBuffer,
                    (dstWidth + 15) / 16, (dstHeight + 15) / 16, 1);
            }
        }

        public boolean testOcclusion(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                                     Matrix4f projMatrix, Matrix4f viewMatrix) {
            if (!enabled) return true;
            if (hizReducePipeline == null || mipImages == null) return true;

            Matrix4f proj = new Matrix4f(projMatrix);
            Matrix4f view = new Matrix4f(viewMatrix);
            Matrix4f vp = new Matrix4f(proj).mul(view);

            Vector4f[] corners = new Vector4f[8];
            corners[0] = new Vector4f(minX, minY, minZ, 1.0f);
            corners[1] = new Vector4f(maxX, minY, minZ, 1.0f);
            corners[2] = new Vector4f(minX, maxY, minZ, 1.0f);
            corners[3] = new Vector4f(maxX, maxY, minZ, 1.0f);
            corners[4] = new Vector4f(minX, minY, maxZ, 1.0f);
            corners[5] = new Vector4f(maxX, minY, maxZ, 1.0f);
            corners[6] = new Vector4f(minX, maxY, maxZ, 1.0f);
            corners[7] = new Vector4f(maxX, maxY, maxZ, 1.0f);

            float minProjX = Float.MAX_VALUE, maxProjX = Float.MIN_VALUE;
            float minProjY = Float.MAX_VALUE, maxProjY = Float.MIN_VALUE;
            float minProjZ = Float.MAX_VALUE;

            for (int i = 0; i < 8; i++) {
                Vector4f clip = vp.transform(corners[i]);
                float invW = 1.0f / Math.max(clip.w, 0.00001f);
                float ndcX = clip.x * invW;
                float ndcY = clip.y * invW;
                float ndcZ = clip.z * invW;

                if (ndcX < -1.0f || ndcX > 1.0f || ndcY < -1.0f || ndcY > 1.0f) {
                    return true;
                }

                float screenX = ndcX * 0.5f + 0.5f;
                float screenY = ndcY * 0.5f + 0.5f;
                float depthZ = ndcZ * 0.5f + 0.5f;

                if (screenX < minProjX) minProjX = screenX;
                if (screenX > maxProjX) maxProjX = screenX;
                if (screenY < minProjY) minProjY = screenY;
                if (screenY > maxProjY) maxProjY = screenY;
                if (depthZ < minProjZ) minProjZ = depthZ;
            }

            if (minProjZ >= 1.0f) return false;

            float rectW = (maxProjX - minProjX) * width;
            float rectH = (maxProjY - minProjY) * height;
            float maxRect = Math.max(rectW, rectH);
            int mipLevel = (int) (Math.log(Math.max(maxRect, 1.0f)) / Math.log(2));
            mipLevel = Math.min(mipLevel, mipLevels - 1);

            if (mipImages[mipLevel] == null) return true;

            return true;
        }

        private String generateHiZSource() {
            return """
                #version 450
                layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
                layout(binding = 0, r32f) uniform readonly image2D depthSrc;
                layout(binding = 1, r32f) uniform writeonly image2D depthDst;
                void main() {
                    ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                    ivec2 srcSize = imageSize(depthSrc);
                    ivec2 dstSize = imageSize(depthDst);
                    if (coord.x >= dstSize.x || coord.y >= dstSize.y) return;
                    ivec2 srcCoord = coord * 2;
                    float d0 = imageLoad(depthSrc, srcCoord).r;
                    float d1 = imageLoad(depthSrc, srcCoord + ivec2(1, 0)).r;
                    float d2 = imageLoad(depthSrc, srcCoord + ivec2(0, 1)).r;
                    float d3 = imageLoad(depthSrc, srcCoord + ivec2(1, 1)).r;
                    float minDepth = min(min(d0, d1), min(d2, d3));
                    if (srcCoord.x + 1 >= srcSize.x) minDepth = min(d0, d2);
                    if (srcCoord.y + 1 >= srcSize.y) minDepth = min(d0, d1);
                    imageStore(depthDst, coord, vec4(minDepth));
                }
                """;
        }

        public void setEnabled(boolean e) {
            this.enabled = e;
            if (!e) cleanup();
        }

        public boolean isEnabled() { return enabled; }

        public void cleanup() {
            if (hizReducePipeline != null) hizReducePipeline.destroy();
            if (mipImages != null) {
                for (VulkanImage img : mipImages) {
                    if (img != null) img.free();
                }
                mipImages = null;
            }
        }
    }

    public static class OcclusionQueryPool {
        private long queryPool;
        private static final int MAX_QUERIES = 1024;
        private int queryIndex = 0;
        private final boolean[] results = new boolean[MAX_QUERIES];
        private final long[] queryTimestamps = new long[MAX_QUERIES];
        private VkDevice device;

        void initialize() {
            device = Vulkan.getVkDevice();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkQueryPoolCreateInfo queryPoolInfo = VkQueryPoolCreateInfo.calloc(stack);
                queryPoolInfo.sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO);
                queryPoolInfo.queryType(VK_QUERY_TYPE_OCCLUSION);
                queryPoolInfo.queryCount(MAX_QUERIES);
                queryPoolInfo.pipelineStatistics(0);

                LongBuffer pQueryPool = stack.mallocLong(1);
                int result = vkCreateQueryPool(device, queryPoolInfo, null, pQueryPool);
                if (result != VK_SUCCESS) {
                    queryPool = MemoryUtil.NULL;
                    return;
                }
                queryPool = pQueryPool.get(0);
            }
        }

        void beginFrame() {
            queryIndex = 0;
        }

        int issueQuery(VkCommandBuffer cmd, float minX, float minY, float minZ,
                       float maxX, float maxY, float maxZ) {
            if (queryPool == MemoryUtil.NULL) return -1;
            if (queryIndex >= MAX_QUERIES) return -1;
            if (queryIndex == 0 && queryPool != MemoryUtil.NULL) {
                vkCmdResetQueryPool(cmd, queryPool, 0, MAX_QUERIES);
            }
            int idx = queryIndex++;

            vkCmdBeginQuery(cmd, queryPool, idx, 0);
            Vector3f center = new Vector3f(
                (minX + maxX) * 0.5f,
                (minY + maxY) * 0.5f,
                (minZ + maxZ) * 0.5f
            );
            Vector3f halfExtent = new Vector3f(
                (maxX - minX) * 0.5f,
                (maxY - minY) * 0.5f,
                (maxZ - minZ) * 0.5f
            );
            vkCmdSetDepthBounds(cmd, 0.0f, 1.0f);
            vkCmdEndQuery(cmd, queryPool, idx);

            results[idx] = false;
            queryTimestamps[idx] = System.nanoTime();
            return idx;
        }

        boolean getQueryResult(int queryId) {
            if (queryPool == MemoryUtil.NULL) return true;
            if (queryId < 0 || queryId >= MAX_QUERIES) return true;

            if (results[queryId]) return true;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer pBuffer = stack.mallocLong(1);
                int result = vkGetQueryPoolResults(device, queryPool, queryId, 1,
                    pBuffer, 8, VK_QUERY_RESULT_WAIT_BIT);
                if (result == VK_SUCCESS) {
                    long sampleCount = pBuffer.get(0);
                    boolean visible = sampleCount > 0;
                    results[queryId] = visible;
                    return visible;
                }
            }
            return true;
        }

        void cleanup() {
            if (queryPool != MemoryUtil.NULL) {
                vkDestroyQueryPool(device, queryPool, null);
                queryPool = MemoryUtil.NULL;
            }
        }
    }
}
