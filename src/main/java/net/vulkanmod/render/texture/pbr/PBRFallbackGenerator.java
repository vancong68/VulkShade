package net.vulkanmod.render.texture.pbr;

import static net.vulkanmod.render.texture.pbr.PBRMaterialDetector.BlockMaterialType;

public final class PBRFallbackGenerator {

    private PBRFallbackGenerator() {}

    public static PBRMaterial generate(BlockMaterialType type) {
        return switch (type) {
            case STONE     -> new PBRMaterial(0.90f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case DIRT      -> new PBRMaterial(0.95f, 0.0f,  0.04f, 0.9f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case WOOD      -> new PBRMaterial(0.85f, 0.0f,  0.04f, 0.8f, 0.0f, 0.0f, 0.0f, 0.2f, 0);
            case METAL     -> new PBRMaterial(0.30f, 1.0f,  0.50f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 255);
            case GLASS     -> new PBRMaterial(0.05f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case WATER     -> new PBRMaterial(0.00f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0);
            case LEAVES    -> new PBRMaterial(0.90f, 0.0f,  0.04f, 0.7f, 0.0f, 0.0f, 0.3f, 0.0f, 0);
            case SAND      -> new PBRMaterial(0.95f, 0.0f,  0.04f, 0.9f, 0.0f, 0.0f, 0.0f, 0.5f, 0);
            case GRAVEL    -> new PBRMaterial(0.95f, 0.0f,  0.04f, 0.9f, 0.0f, 0.0f, 0.0f, 0.5f, 0);
            case SNOW      -> new PBRMaterial(0.95f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case ICE       -> new PBRMaterial(0.00f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case WOOL      -> new PBRMaterial(0.95f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0);
            case TERRACOTTA -> new PBRMaterial(0.90f, 0.0f, 0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case PLANT     -> new PBRMaterial(0.90f, 0.0f,  0.04f, 0.8f, 0.0f, 0.0f, 0.3f, 0.0f, 0);
            case ORE       -> new PBRMaterial(0.70f, 0.5f,  0.08f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 250);
            case GEM       -> new PBRMaterial(0.10f, 0.0f,  0.06f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case BONE      -> new PBRMaterial(0.80f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case NETHER    -> new PBRMaterial(0.85f, 0.0f,  0.04f, 0.8f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case ENDSTONE  -> new PBRMaterial(0.95f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case MUD       -> new PBRMaterial(0.95f, 0.0f,  0.04f, 0.7f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case CONCRETE  -> new PBRMaterial(0.80f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case CALCITE   -> new PBRMaterial(0.30f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case DRIPSTONE -> new PBRMaterial(0.85f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case DEEPSLATE -> new PBRMaterial(0.90f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case OBSIDIAN  -> new PBRMaterial(0.10f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case ANVIL     -> new PBRMaterial(0.40f, 0.8f,  0.30f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 235);
            case HONEY     -> new PBRMaterial(0.30f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.3f, 0);
            case SLIME     -> new PBRMaterial(0.40f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case SPONGE    -> new PBRMaterial(0.95f, 0.0f,  0.04f, 0.6f, 0.0f, 0.0f, 0.0f, 0.8f, 0);
            case MUSHROOM  -> new PBRMaterial(0.85f, 0.0f,  0.04f, 0.9f, 0.0f, 0.0f, 0.1f, 0.0f, 0);
            case BASALT    -> new PBRMaterial(0.80f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case PACKED_ICE -> new PBRMaterial(0.05f, 0.0f, 0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case TINTED_GLASS -> new PBRMaterial(0.05f, 0.0f, 0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case COPPER    -> new PBRMaterial(0.40f, 0.9f,  0.45f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 234);
            case UNKNOWN   -> PBRMaterial.DEFAULT.copy();
        };
    }
}
