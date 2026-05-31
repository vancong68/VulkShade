package net.vulkanmod.vulkshade.optimization;

import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.*;

public class ChunkBatchManager {
    private static final Logger LOGGER = LogManager.getLogger("VulkShade-ChunkBatch");
    private static ChunkBatchManager INSTANCE;

    private static final float NEAR_DISTANCE = 32.0f;
    private static final float MID_DISTANCE = 96.0f;

    private final BatchGroup[][] batchGroups;

    private int maxBatchSize = 128;
    private boolean mergeEnabled = true;
    private long totalDrawCalls = 0;
    private long mergedDrawCalls = 0;
    private int nearBatchCount = 0;
    private int midBatchCount = 0;
    private int farBatchCount = 0;

    public ChunkBatchManager() {
        TerrainRenderType[] types = TerrainRenderType.values();
        batchGroups = new BatchGroup[types.length][3];
        for (int i = 0; i < types.length; i++) {
            for (int j = 0; j < 3; j++) {
                batchGroups[i][j] = new BatchGroup();
            }
        }
    }

    public static ChunkBatchManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ChunkBatchManager();
        }
        return INSTANCE;
    }

    public void beginFrame() {
        for (int i = 0; i < batchGroups.length; i++) {
            for (int j = 0; j < 3; j++) {
                batchGroups[i][j].reset();
            }
        }
        totalDrawCalls = 0;
        mergedDrawCalls = 0;
        nearBatchCount = 0;
        midBatchCount = 0;
        farBatchCount = 0;
    }

    public void addChunkArea(ChunkArea area, TerrainRenderType renderType, Vec3 cameraPos) {
        if (!mergeEnabled) {
            totalDrawCalls++;
            mergedDrawCalls++;
            return;
        }

        float dx = area.getPosition().x() + 64 - (float) cameraPos.x;
        float dz = area.getPosition().z() + 64 - (float) cameraPos.z;
        float distSq = dx * dx + dz * dz;
        float dist = (float) Math.sqrt(distSq);

        int distanceTier;
        if (dist < NEAR_DISTANCE) {
            distanceTier = 0;
            nearBatchCount++;
        } else if (dist < MID_DISTANCE) {
            distanceTier = 1;
            midBatchCount++;
        } else {
            distanceTier = 2;
            farBatchCount++;
        }

        BatchGroup group = batchGroups[renderType.ordinal()][distanceTier];
        group.addArea(area);
    }

    public void flushBatches(VkCommandBuffer cmdBuffer, Vec3 cameraPos) {
        if (!mergeEnabled) return;

        for (int i = 0; i < batchGroups.length; i++) {
            TerrainRenderType renderType = TerrainRenderType.values()[i];
            if (renderType == TerrainRenderType.TRANSLUCENT) {
                flushGroup(cmdBuffer, batchGroups[i][0], renderType, cameraPos);
                flushGroup(cmdBuffer, batchGroups[i][1], renderType, cameraPos);
                flushGroup(cmdBuffer, batchGroups[i][2], renderType, cameraPos);
            } else {
                for (int j = 0; j < 3; j++) {
                    flushGroup(cmdBuffer, batchGroups[i][j], renderType, cameraPos);
                }
            }
        }
    }

    private void flushGroup(VkCommandBuffer cmdBuffer, BatchGroup group, TerrainRenderType renderType, Vec3 cameraPos) {
        if (group.areas.isEmpty()) return;

        int batchCount = 0;
        for (ChunkArea area : group.areas) {
            DrawBuffers db = area.getDrawBuffers();
            if (db == null) continue;
            if (area.sectionQueue == null || area.sectionQueue.size() == 0) continue;

            db.buildDrawBatchesDirect(cameraPos, area.sectionQueue, renderType);
            batchCount++;
        }

        totalDrawCalls += batchCount;
        mergedDrawCalls += (batchCount > 0 ? 1 : 0);
    }

    public long getTotalDrawCalls() { return totalDrawCalls; }
    public long getMergedDrawCalls() { return mergedDrawCalls; }
    public float getMergeRatio() {
        return totalDrawCalls > 0 ? (float) mergedDrawCalls / totalDrawCalls * 100f : 100f;
    }

    public void setMergeEnabled(boolean enabled) { this.mergeEnabled = enabled; }
    public boolean isMergeEnabled() { return mergeEnabled; }
    public void setMaxBatchSize(int size) { this.maxBatchSize = Math.max(16, size); }

    public String getStats() {
        return String.format("Near=%d Mid=%d Far=%d Draws=%d Merged=%d Ratio=%.1f%%",
            nearBatchCount, midBatchCount, farBatchCount,
            totalDrawCalls, mergedDrawCalls, getMergeRatio());
    }

    public static class BatchGroup {
        final List<ChunkArea> areas = new ArrayList<>();
        int totalVertices = 0;

        void addArea(ChunkArea area) {
            areas.add(area);
        }

        boolean isEmpty() { return areas.isEmpty(); }

        void reset() {
            areas.clear();
            totalVertices = 0;
        }
    }
}
