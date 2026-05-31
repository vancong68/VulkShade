package net.vulkanmod.vulkshade.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.VBO;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoxyLODManager {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-VoxyLOD");
    private static VoxyLODManager INSTANCE;

    private boolean enabled = true;
    private LODQuality quality = LODQuality.MEDIUM;
    private int maxViewDistance = 512;
    private int tileSize = 16;
    private int superTileSize = 8;

    private final ExecutorService lodBuilder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VulkShade-LOD-Builder");
        t.setDaemon(true);
        return t;
    });

    private final Map<Long, LODTile> lodTiles = new ConcurrentHashMap<>();
    private final Queue<LODTile> uploadQueue = new ArrayDeque<>();

    private float cameraX, cameraZ;
    private int originTileX, originTileZ;
    private int renderRadiusTiles;
    private long frameCount = 0;
    private int renderedVertices = 0;

    private GraphicsPipeline lodPipeline;

    public VoxyLODManager() {
    }

    public static VoxyLODManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new VoxyLODManager();
        }
        return INSTANCE;
    }

    public void initialize() {
        reloadConfig();
        this.renderRadiusTiles = maxViewDistance / (tileSize * superTileSize) + 2;
        LOGGER.info("VoxyLODManager initialized: enabled={}, quality={}, maxDist={}, tiles={}x{}",
            enabled, quality, maxViewDistance, renderRadiusTiles * 2 + 1, renderRadiusTiles * 2 + 1);
    }

    public void reloadConfig() {
        net.vulkanmod.vulkshade.config.VulkShadeConfig cfg = net.vulkanmod.vulkshade.config.VulkShadeConfig.getInstance();
        this.enabled = cfg.isVoxyLODEnabled();
        this.quality = cfg.getLODQuality();
        this.maxViewDistance = cfg.getMaxLODViewDistance();
    }

    public void updateCamera(float x, float z) {
        this.cameraX = x;
        this.cameraZ = z;
        this.originTileX = (int) Math.floor(x / (tileSize * superTileSize));
        this.originTileZ = (int) Math.floor(z / (tileSize * superTileSize));
    }

    public void updateTiles() {
        if (!enabled) return;

        if (lodPipeline == null) {
            this.lodPipeline = PipelineManager.getCloudsPipeline();
            if (this.lodPipeline == null) return;
        }

        int radius = renderRadiusTiles;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int tileX = originTileX + dx;
                int tileZ = originTileZ + dz;
                long key = tileKey(tileX, tileZ);

                float worldX = tileX * tileSize * superTileSize;
                float worldZ = tileZ * tileSize * superTileSize;
                float dist = (float) Math.sqrt(
                    (worldX - cameraX) * (worldX - cameraX) +
                    (worldZ - cameraZ) * (worldZ - cameraZ));

                int lodLevel = computeLODLevel(dist);

                LODTile tile = lodTiles.get(key);
                if (tile == null) {
                    tile = new LODTile(tileX, tileZ);
                    lodTiles.put(key, tile);
                    scheduleTileBuild(tile, lodLevel);
                } else if (tile.getLODLevel() != lodLevel && !tile.isBuilding()) {
                    tile.setTargetLOD(lodLevel);
                    scheduleTileBuild(tile, lodLevel);
                }

                tile.setDistance(dist);
            }
        }

        removeDistantTiles(radius);
        frameCount++;
    }

    private int computeLODLevel(float distance) {
        if (distance < quality.lod1Dist) return 1;
        if (distance < quality.lod2Dist) return 2;
        if (distance < quality.lod3Dist) return 3;
        return 3;
    }

    public void scheduleTileBuild(LODTile tile, int lodLevel) {
        tile.setBuilding(true);
        tile.setLODLevel(lodLevel);

        int tx = tile.getTileX() * tileSize * superTileSize;
        int tz = tile.getTileZ() * tileSize * superTileSize;

        lodBuilder.submit(() -> {
            try {
                switch (lodLevel) {
                    case 1 -> buildLOD1(tile, tx, tz);
                    case 2 -> buildLOD2(tile, tx, tz);
                    case 3 -> buildLOD3(tile, tx, tz);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to build LOD tile ({},{}): {}", tx, tz, e.getMessage());
            } finally {
                tile.setBuilding(false);
            }
        });
    }

    private void buildLOD1(LODTile tile, int worldX, int worldZ) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;

        int cells = 8;
        int step = 16;
        int vertsX = cells + 1;
        int vertsZ = cells + 1;
        float[] heights = new float[vertsX * vertsZ];
        int[] colors = new int[vertsX * vertsZ];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int iz = 0; iz < vertsZ; iz++) {
            for (int ix = 0; ix < vertsX; ix++) {
                int bx = worldX + ix * step;
                int bz = worldZ + iz * step;
                int idx = iz * vertsX + ix;
                heights[idx] = sampleHeight(level, pos, bx, bz);
                colors[idx] = sampleColor(level, pos, bx, (int) heights[idx], bz);
            }
        }

        int[] indices = new int[cells * cells * 6];
        int idx = 0;
        for (int iz = 0; iz < cells; iz++) {
            for (int ix = 0; ix < cells; ix++) {
                int bl = iz * vertsX + ix;
                int br = iz * vertsX + ix + 1;
                int tl = (iz + 1) * vertsX + ix;
                int tr = (iz + 1) * vertsX + ix + 1;
                indices[idx++] = bl; indices[idx++] = br; indices[idx++] = tl;
                indices[idx++] = br; indices[idx++] = tr; indices[idx++] = tl;
            }
        }

        tile.setMeshData(heights, colors, vertsX, vertsZ, indices);
        tile.setVertexCount(vertsX * vertsZ);
        tile.setDrawCalls(cells * cells * 2);
        tile.setBuilt(true);
    }

    private void buildLOD2(LODTile tile, int worldX, int worldZ) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;

        int cells = 4;
        int step = 32;
        int vertsX = cells + 1;
        int vertsZ = cells + 1;
        float[] heights = new float[vertsX * vertsZ];
        int[] colors = new int[vertsX * vertsZ];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int iz = 0; iz < vertsZ; iz++) {
            for (int ix = 0; ix < vertsX; ix++) {
                int bx = worldX + ix * step;
                int bz = worldZ + iz * step;
                int idx = iz * vertsX + ix;

                float hSum = 0; int hCount = 0;
                int rSum = 0, gSum = 0, bSum = 0;
                for (int sz = 0; sz < step; sz += 8) {
                    for (int sx = 0; sx < step; sx += 8) {
                        float h = sampleHeight(level, pos, bx + sx, bz + sz);
                        hSum += h; hCount++;
                        int c = sampleColor(level, pos, bx + sx, (int) h, bz + sz);
                        rSum += (c >> 16) & 0xFF; gSum += (c >> 8) & 0xFF; bSum += c & 0xFF;
                    }
                }
                heights[idx] = hCount > 0 ? hSum / hCount : level.getMinY();
                if (hCount > 0) {
                    colors[idx] = ((rSum / hCount) << 16) | ((gSum / hCount) << 8) | (bSum / hCount);
                } else {
                    colors[idx] = 0x888888;
                }
            }
        }

        int[] indices = new int[cells * cells * 6];
        int idx = 0;
        for (int iz = 0; iz < cells; iz++) {
            for (int ix = 0; ix < cells; ix++) {
                int bl = iz * vertsX + ix;
                int br = iz * vertsX + ix + 1;
                int tl = (iz + 1) * vertsX + ix;
                int tr = (iz + 1) * vertsX + ix + 1;
                indices[idx++] = bl; indices[idx++] = br; indices[idx++] = tl;
                indices[idx++] = br; indices[idx++] = tr; indices[idx++] = tl;
            }
        }

        tile.setMeshData(heights, colors, vertsX, vertsZ, indices);
        tile.setVertexCount(vertsX * vertsZ);
        tile.setDrawCalls(cells * cells * 2);
        tile.setBuilt(true);
    }

    private void buildLOD3(LODTile tile, int worldX, int worldZ) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;

        int cells = 2;
        int step = 64;
        int vertsX = cells + 1;
        int vertsZ = cells + 1;
        float[] heights = new float[vertsX * vertsZ];
        int[] colors = new int[vertsX * vertsZ];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int iz = 0; iz < vertsZ; iz++) {
            for (int ix = 0; ix < vertsX; ix++) {
                int bx = worldX + ix * step;
                int bz = worldZ + iz * step;
                int idx = iz * vertsX + ix;

                float hSum = 0; int hCount = 0;
                int rSum = 0, gSum = 0, bSum = 0;
                for (int sz = 0; sz < step; sz += 16) {
                    for (int sx = 0; sx < step; sx += 16) {
                        float h = sampleHeight(level, pos, bx + sx, bz + sz);
                        hSum += h; hCount++;
                        int c = sampleColor(level, pos, bx + sx, (int) h, bz + sz);
                        rSum += (c >> 16) & 0xFF; gSum += (c >> 8) & 0xFF; bSum += c & 0xFF;
                    }
                }
                heights[idx] = hCount > 0 ? hSum / hCount : level.getMinY();
                if (hCount > 0) {
                    colors[idx] = ((rSum / hCount) << 16) | ((gSum / hCount) << 8) | (bSum / hCount);
                } else {
                    colors[idx] = 0x888888;
                }
            }
        }

        int[] indices = new int[cells * cells * 6];
        int idx = 0;
        for (int iz = 0; iz < cells; iz++) {
            for (int ix = 0; ix < cells; ix++) {
                int bl = iz * vertsX + ix;
                int br = iz * vertsX + ix + 1;
                int tl = (iz + 1) * vertsX + ix;
                int tr = (iz + 1) * vertsX + ix + 1;
                indices[idx++] = bl; indices[idx++] = br; indices[idx++] = tl;
                indices[idx++] = br; indices[idx++] = tr; indices[idx++] = tl;
            }
        }

        tile.setMeshData(heights, colors, vertsX, vertsZ, indices);
        tile.setVertexCount(vertsX * vertsZ);
        tile.setDrawCalls(cells * cells * 2);
        tile.setBuilt(true);
    }

    private float sampleHeight(Level level, BlockPos.MutableBlockPos pos, int bx, int bz) {
        for (int y = level.getMaxY(); y > level.getMinY(); y -= 4) {
            pos.set(bx, y, bz);
            if (!level.getBlockState(pos).isAir()) {
                return y;
            }
        }
        return level.getMinY();
    }

    private int sampleColor(Level level, BlockPos.MutableBlockPos pos, int bx, int y, int bz) {
        pos.set(bx, y, bz);
        BlockState state = level.getBlockState(pos);
        return state.getMapColor(level, pos).col;
    }

    private void removeDistantTiles(int radius) {
        int margin = 4;
        lodTiles.values().removeIf(tile -> {
            int dx = tile.getTileX() - originTileX;
            int dz = tile.getTileZ() - originTileZ;
            if (Math.abs(dx) > radius + margin || Math.abs(dz) > radius + margin) {
                if (tile.vbo != null) {
                    tile.vbo.close();
                    tile.vbo = null;
                }
                return true;
            }
            return false;
        });
    }

    private void processUploads() {
        LODTile tile;
        while ((tile = uploadQueue.poll()) != null) {
            if (tile.meshVerticesX <= 0 || tile.meshData != null) continue;

            if (tile.vbo != null) {
                tile.vbo.close();
            }

            tile.vbo = buildVBOFromMesh(tile);
            tile.meshHeights = null;
            tile.meshColors = null;
            tile.meshIndices = null;
        }
    }

    private VBO buildVBOFromMesh(LODTile tile) {
        int vertsX = tile.meshVerticesX;
        int vertsZ = tile.meshVerticesZ;

        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float tileWorldX = tile.getTileX() * tileSize * superTileSize;
        float tileWorldZ = tile.getTileZ() * tileSize * superTileSize;
        float cellW = (float) (tileSize * superTileSize) / (vertsX - 1);
        float cellD = (float) (tileSize * superTileSize) / (vertsZ - 1);

        int[] indices = tile.meshIndices;
        for (int i = 0; i < indices.length; i += 6) {
            int bl = indices[i];
            int br = indices[i + 1];
            int tr = indices[i + 4];
            int tl = indices[i + 2];

            float[][] verts = {
                { tileWorldX + (bl % vertsX) * cellW, tile.meshHeights[bl], tileWorldZ + (bl / vertsX) * cellD, bl },
                { tileWorldX + (br % vertsX) * cellW, tile.meshHeights[br], tileWorldZ + (br / vertsX) * cellD, br },
                { tileWorldX + (tr % vertsX) * cellW, tile.meshHeights[tr], tileWorldZ + (tr / vertsX) * cellD, tr },
                { tileWorldX + (tl % vertsX) * cellW, tile.meshHeights[tl], tileWorldZ + (tl / vertsX) * cellD, tl }
            };

            for (float[] v : verts) {
                int c = tile.meshColors[(int) v[3]];
                int abgr = 0xFF000000 | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF);
                buffer.addVertex(v[0], v[1], v[2]).setColor(abgr);
            }
        }

        MeshData meshData = buffer.build();
        VBO vbo = new VBO(true);
        vbo.upload(meshData);
        return vbo;
    }

    public void renderLODTiles() {
        if (!enabled) return;

        processUploads();

        if (lodTiles.isEmpty()) return;
        if (lodPipeline == null) {
            this.lodPipeline = PipelineManager.getCloudsPipeline();
            if (this.lodPipeline == null) return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        float px = (float) mc.player.getX();
        float pz = (float) mc.player.getZ();

        VRenderSystem.enableDepthTest();
        VRenderSystem.depthFunc(515);
        VRenderSystem.disableBlend();

        int drawnVerts = 0;
        for (LODTile tile : lodTiles.values()) {
            if (!tile.isBuilt() || tile.vbo == null) continue;
            if (tile.getLODLevel() == 0) continue;

            float dx = tile.getTileX() * tileSize * superTileSize + tileSize * superTileSize / 2.0f - px;
            float dz = tile.getTileZ() * tileSize * superTileSize + tileSize * superTileSize / 2.0f - pz;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            float mcRenderDist = mc.options.renderDistance().get() * 16.0f;
            if (dist < mcRenderDist * 0.8f) continue;

            tile.vbo.bind(lodPipeline);
            tile.vbo.draw();
            drawnVerts += tile.getVertexCount();
        }

        this.renderedVertices = drawnVerts;
    }

    public void renderDebugOverlay() {
    }

    public void cleanup() {
        lodBuilder.shutdown();
        for (LODTile tile : lodTiles.values()) {
            if (tile.vbo != null) {
                tile.vbo.close();
                tile.vbo = null;
            }
        }
        lodTiles.clear();
        uploadQueue.clear();
        LOGGER.info("VoxyLODManager cleaned up");
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { this.enabled = e; }
    public LODQuality getQuality() { return quality; }
    public void setQuality(LODQuality q) { this.quality = q; }
    public int getMaxViewDistance() { return maxViewDistance; }
    public void setMaxViewDistance(int d) { this.maxViewDistance = d; }
    public int getActiveTileCount() { return lodTiles.size(); }
    public int getBuiltTileCount() {
        return (int) lodTiles.values().stream().filter(LODTile::isBuilt).count();
    }
    public int getRenderedVertices() { return renderedVertices; }

    private static long tileKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public record LODQuality(String name, float lod1Dist, float lod2Dist, float lod3Dist, float blendWidth) {
        public static final LODQuality LOW = new LODQuality("Low", 48, 128, 384, 16);
        public static final LODQuality MEDIUM = new LODQuality("Medium", 64, 192, 512, 24);
        public static final LODQuality HIGH = new LODQuality("High", 96, 384, 768, 32);
    }

    public static class LODTile {
        private final int tileX, tileZ;
        private int lodLevel = 0;
        private int targetLOD = 0;
        private float distance;
        private volatile boolean built = false;
        private volatile boolean building = false;
        private int vertexCount;
        private int drawCalls;
        VBO vbo;

        float[] meshHeights;
        int[] meshColors;
        int meshVerticesX;
        int meshVerticesZ;
        int[] meshIndices;
        ByteBuffer meshData;

        LODTile(int x, int z) {
            this.tileX = x;
            this.tileZ = z;
        }

        void setMeshData(float[] heights, int[] colors, int vx, int vz, int[] indices) {
            this.meshHeights = heights;
            this.meshColors = colors;
            this.meshVerticesX = vx;
            this.meshVerticesZ = vz;
            this.meshIndices = indices;
        }

        public int getTileX() { return tileX; }
        public int getTileZ() { return tileZ; }
        public int getLODLevel() { return lodLevel; }
        public void setLODLevel(int level) { this.lodLevel = level; }
        public int getTargetLOD() { return targetLOD; }
        public void setTargetLOD(int lod) { this.targetLOD = lod; }
        public float getDistance() { return distance; }
        public void setDistance(float d) { this.distance = d; }
        public boolean isBuilt() { return built; }
        public void setBuilt(boolean b) { this.built = b; }
        public boolean isBuilding() { return building; }
        public void setBuilding(boolean b) { this.building = b; }
        public int getVertexCount() { return vertexCount; }
        public void setVertexCount(int v) { this.vertexCount = v; }
        public int getDrawCalls() { return drawCalls; }
        public void setDrawCalls(int d) { this.drawCalls = d; }
    }
}
