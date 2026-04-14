package net.vulkanmod.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.ShortDryGrassBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class TerrainBuilder {
    private static final Logger LOGGER = Initializer.LOGGER;
    private static final MemoryUtil.MemoryAllocator ALLOCATOR = MemoryUtil.getAllocator(false);
    public static final int MATERIAL_WAVING = 0x4;
    public static final int MATERIAL_WAVING_SPECIAL = 0x8;
    public static final int FOLIAGE_NONE = 0x0;
    public static final int FOLIAGE_BASIC = MATERIAL_WAVING;
    public static final int FOLIAGE_LEAVES = MATERIAL_WAVING_SPECIAL;
    public static final int FOLIAGE_UPPER = MATERIAL_WAVING | MATERIAL_WAVING_SPECIAL;

    protected long indexBufferPtr;

    private int indexBufferCapacity;

    private final VertexFormat format;

    private boolean building;

    private final QuadSorter quadSorter = new QuadSorter();

    private boolean needsSorting;
    private boolean indexOnly;
    private int blockMaterialType;

    protected VertexBuilder vertexBuilder;

    private final TerrainBufferBuilder[] bufferBuilders;

    public TerrainBuilder(int size, VertexBuilder vertexBuilder) {
        // FIXME: same size is used for both index and vertex buffers
        this.indexBufferCapacity = size;
        this.indexBufferPtr = ALLOCATOR.malloc(this.indexBufferCapacity);

        this.format = PipelineManager.terrainVertexFormat;
        this.vertexBuilder = vertexBuilder;

        var bufferBuilders = new TerrainBufferBuilder[QuadFacing.COUNT];
        for (int i = 0; i < QuadFacing.COUNT; i++) {
            bufferBuilders[i] = new TerrainBufferBuilder(size, this.format.getVertexSize(), this.vertexBuilder);
        }

        this.bufferBuilders = bufferBuilders;
    }

    public TerrainBufferBuilder getBufferBuilder(int i) {
        return this.bufferBuilders[i];
    }

    private void ensureIndexCapacity(int size) {
        if (size > this.indexBufferCapacity) {
            int capacity = this.indexBufferCapacity;
            int newSize = (capacity + size) * 2;
            this.resizeIndexBuffer(newSize);
        }
    }

    private void resizeIndexBuffer(int i) {
        this.indexBufferPtr = ALLOCATOR.realloc(this.indexBufferPtr, i);
        LOGGER.debug("Needed to grow index buffer: Old size {} bytes, new size {} bytes.", this.indexBufferCapacity, i);
        if (this.indexBufferPtr == 0L) {
            throw new OutOfMemoryError("Failed to resize buffer from " + this.indexBufferCapacity + " bytes to " + i + " bytes");
        } else {
            this.indexBufferCapacity = i;
        }
    }

    public void setupQuadSorting(float x, float y, float z) {
        this.quadSorter.setQuadSortOrigin(x, y, z);
        this.needsSorting = true;
    }

    public QuadSorter.SortState getSortState() {
        return this.quadSorter.getSortState();
    }

    public void restoreSortState(QuadSorter.SortState sortState) {
        this.quadSorter.restoreSortState(sortState);

        this.indexOnly = true;
    }

    public void setIndexOnly() {
        this.indexOnly = true;
    }

    public void begin() {
        if (this.building) {
            throw new IllegalStateException("Already building!");
        } else {
            this.building = true;
        }
    }

    public void setupQuadSortingPoints() {
        TerrainBufferBuilder bufferBuilder = bufferBuilders[QuadFacing.UNDEFINED.ordinal()];
        long bufferPtr = bufferBuilder.getPtr();
        int vertexCount = bufferBuilder.getVertices();

        this.quadSorter.setupQuadSortingPoints(bufferPtr, vertexCount, this.format);
    }

    public DrawState endDrawing() {
        for (TerrainBufferBuilder bufferBuilder : this.bufferBuilders) {
            bufferBuilder.end();
        }

        int vertexCount = this.quadSorter.getVertexCount();

        int indexCount = vertexCount / 4 * 6;

        VertexFormat.IndexType indexType = VertexFormat.IndexType.least(indexCount);
        boolean sequentialIndexing;

        // TODO sorting
        if (this.needsSorting) {
            int indexBufferSize = indexCount * indexType.bytes;
            this.ensureIndexCapacity(indexBufferSize);

            this.quadSorter.putSortedQuadIndices(this, indexType);

            sequentialIndexing = false;
        } else {
            sequentialIndexing = true;
        }

        return new DrawState(this.format.getVertexSize(), indexCount, indexType, this.indexOnly, sequentialIndexing);
    }

    // TODO hardcoded index type size
    public ByteBuffer getIndexBuffer() {
        int indexCount = this.quadSorter.getVertexCount() * 6 / 4;

        return MemoryUtil.memByteBuffer(this.indexBufferPtr, indexCount * 2);
    }

    private void ensureDrawing() {
        if (!this.building) {
            throw new IllegalStateException("Not building!");
        }
    }

    public void reset() {
        this.building = false;

        this.indexOnly = false;
        this.needsSorting = false;
    }

    public void clear() {
        this.reset();
        this.blockMaterialType = FOLIAGE_NONE;

        for (TerrainBufferBuilder bufferBuilder : this.bufferBuilders) {
            bufferBuilder.clear();
        }
    }

    public void free() {
        ALLOCATOR.free(this.indexBufferPtr);

        for (TerrainBufferBuilder bufferBuilder : this.bufferBuilders) {
            bufferBuilder.free();
        }
    }

    public void setBlockAttributes(BlockState blockState) {
        this.blockMaterialType = classifyBlockMaterial(blockState);
    }

    public int getVertexMaterialFlags(float localY) {
        return switch (this.blockMaterialType) {
            case FOLIAGE_BASIC -> localY > 0.5f ? FOLIAGE_BASIC : FOLIAGE_NONE;
            case FOLIAGE_LEAVES -> FOLIAGE_LEAVES;
            case FOLIAGE_UPPER -> FOLIAGE_UPPER;
            default -> FOLIAGE_NONE;
        };
    }

    private static int classifyBlockMaterial(BlockState blockState) {
        if (blockState.is(BlockTags.LEAVES) || blockState.getBlock() instanceof LeavesBlock) {
            return FOLIAGE_LEAVES;
        }

        boolean isWavingPlant = blockState.getBlock() instanceof DoublePlantBlock
            || blockState.getBlock() instanceof TallGrassBlock
            || blockState.getBlock() instanceof TallFlowerBlock
            || blockState.getBlock() instanceof CropBlock
            || blockState.getBlock() instanceof SaplingBlock
            || blockState.getBlock() instanceof ShortDryGrassBlock
            || blockState.getBlock() instanceof BushBlock
            || blockState.is(BlockTags.FLOWERS)
            || blockState.is(BlockTags.CROPS)
            || blockState.is(BlockTags.SAPLINGS);

        if (!isWavingPlant) {
            return FOLIAGE_NONE;
        }

        if (blockState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && blockState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return FOLIAGE_UPPER;
        }

        return FOLIAGE_BASIC;
    }

    public record DrawState(int vertexSize, int indexCount, VertexFormat.IndexType indexType,
                            boolean indexOnly, boolean sequentialIndex) {

        private int indexBufferSize() {
            return this.sequentialIndex ? 0 : this.indexCount * this.indexType.bytes;
        }

        public int indexCount() {
            return this.indexCount;
        }

        public VertexFormat.IndexType indexType() {
            return this.indexType;
        }

        public boolean indexOnly() {
            return this.indexOnly;
        }

        public boolean sequentialIndex() {
            return this.sequentialIndex;
        }
    }
}
