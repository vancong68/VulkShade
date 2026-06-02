package net.vulkanmod.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
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
    public static final int MATERIAL_DEFAULT = 0x0;
    public static final int MATERIAL_ROCK = 0x1;
    public static final int MATERIAL_WOOD = 0x2;
    public static final int MATERIAL_METAL = 0x3;
    public static final int MATERIAL_GLASS = 0x4;
    public static final int MATERIAL_LEAF = 0x5;
    public static final int MATERIAL_ORGANIC = 0x6;
    public static final int MATERIAL_SAND = 0x7;
    public static final int MATERIAL_DIRT = 0x8;
    public static final int MATERIAL_WATER = 0x9;
    public static final int MATERIAL_ICE = 0xA;
    public static final int MATERIAL_EMISSIVE = 0xB;

    public static final int FOLIAGE_WAVING = 0x4;
    public static final int FOLIAGE_WAVING_SPECIAL = 0x8;
    public static final int FOLIAGE_NONE = 0x0;
    public static final int FOLIAGE_BASIC = FOLIAGE_WAVING;
    public static final int FOLIAGE_LEAVES = FOLIAGE_WAVING_SPECIAL;
    public static final int FOLIAGE_UPPER = FOLIAGE_WAVING | FOLIAGE_WAVING_SPECIAL;

    protected long indexBufferPtr;

    private int indexBufferCapacity;

    private final VertexFormat format;

    private boolean building;

    private final QuadSorter quadSorter = new QuadSorter();

    private boolean needsSorting;
    private boolean indexOnly;
    private int blockMaterialType;
    private int blockMaterialId;

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
        this.blockMaterialId = MATERIAL_DEFAULT;

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
        this.blockMaterialId = classifyBlockMaterial(blockState);
        this.blockMaterialType = classifyFoliageType(blockState);
    }

    public int getVertexMaterialClass() {
        return this.blockMaterialId;
    }

    public int getVertexMaterialFlags(float y) {
        return this.blockMaterialType;
    }

    private static int classifyFoliageType(BlockState blockState) {
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

    private static int classifyBlockMaterial(BlockState blockState) {
        var block = blockState.getBlock();

        if (block == Blocks.GLOWSTONE
            || block == Blocks.SEA_LANTERN
            || block == Blocks.BEACON
            || block == Blocks.REDSTONE_LAMP
            || block == Blocks.SHROOMLIGHT
            || block == Blocks.JACK_O_LANTERN
            || block == Blocks.MAGMA_BLOCK) {
            return MATERIAL_EMISSIVE;
        }

        if (blockState.is(BlockTags.LEAVES) || block instanceof LeavesBlock) {
            return MATERIAL_LEAF;
        }

        if (blockState.is(BlockTags.LOGS) || blockState.is(BlockTags.PLANKS)) {
            return MATERIAL_WOOD;
        }

        if (block == Blocks.IRON_BLOCK
            || block == Blocks.GOLD_BLOCK
            || block == Blocks.COPPER_BLOCK
            || block == Blocks.NETHERITE_BLOCK
            || blockState.is(BlockTags.ANVIL)) {
            return MATERIAL_METAL;
        }

        if (block == Blocks.GLASS || block == Blocks.WHITE_STAINED_GLASS
            || block == Blocks.ORANGE_STAINED_GLASS || block == Blocks.MAGENTA_STAINED_GLASS
            || block == Blocks.LIGHT_BLUE_STAINED_GLASS || block == Blocks.YELLOW_STAINED_GLASS
            || block == Blocks.LIME_STAINED_GLASS || block == Blocks.PINK_STAINED_GLASS
            || block == Blocks.GRAY_STAINED_GLASS || block == Blocks.LIGHT_GRAY_STAINED_GLASS
            || block == Blocks.CYAN_STAINED_GLASS || block == Blocks.PURPLE_STAINED_GLASS
            || block == Blocks.BLUE_STAINED_GLASS || block == Blocks.BROWN_STAINED_GLASS
            || block == Blocks.GREEN_STAINED_GLASS || block == Blocks.RED_STAINED_GLASS
            || block == Blocks.BLACK_STAINED_GLASS || block == Blocks.TINTED_GLASS
            || block == Blocks.GLASS_PANE || block == Blocks.WHITE_STAINED_GLASS_PANE
            || block == Blocks.ORANGE_STAINED_GLASS_PANE || block == Blocks.MAGENTA_STAINED_GLASS_PANE
            || block == Blocks.LIGHT_BLUE_STAINED_GLASS_PANE || block == Blocks.YELLOW_STAINED_GLASS_PANE
            || block == Blocks.LIME_STAINED_GLASS_PANE || block == Blocks.PINK_STAINED_GLASS_PANE
            || block == Blocks.GRAY_STAINED_GLASS_PANE || block == Blocks.LIGHT_GRAY_STAINED_GLASS_PANE
            || block == Blocks.CYAN_STAINED_GLASS_PANE || block == Blocks.PURPLE_STAINED_GLASS_PANE
            || block == Blocks.BLUE_STAINED_GLASS_PANE || block == Blocks.BROWN_STAINED_GLASS_PANE
            || block == Blocks.GREEN_STAINED_GLASS_PANE || block == Blocks.RED_STAINED_GLASS_PANE
            || block == Blocks.BLACK_STAINED_GLASS_PANE) {
            return MATERIAL_GLASS;
        }

        if (blockState.is(BlockTags.SAND)) {
            return MATERIAL_SAND;
        }

        if (blockState.is(BlockTags.DIRT)
            || block == Blocks.SNOW_BLOCK || block == Blocks.SNOW) {
            return MATERIAL_DIRT;
        }

        if (block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE) {
            return MATERIAL_ICE;
        }

        if (block == Blocks.WATER || blockState.getFluidState().isSource()
            || block == Blocks.BUBBLE_COLUMN) {
            return MATERIAL_WATER;
        }

        if (block instanceof BushBlock
            || block instanceof TallGrassBlock
            || block instanceof TallFlowerBlock
            || block instanceof CropBlock
            || block instanceof SaplingBlock
            || block instanceof ShortDryGrassBlock) {
            return MATERIAL_ORGANIC;
        }

        return MATERIAL_ROCK;
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
