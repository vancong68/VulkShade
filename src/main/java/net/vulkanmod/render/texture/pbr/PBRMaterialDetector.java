package net.vulkanmod.render.texture.pbr;

import net.minecraft.resources.ResourceLocation;

public final class PBRMaterialDetector {

    public enum BlockMaterialType {
        STONE, DIRT, WOOD, METAL, GLASS, WATER, LEAVES, SAND, GRAVEL,
        SNOW, ICE, WOOL, TERRACOTTA, PLANT, ORE, GEM, BONE, NETHER,
        ENDSTONE, MUD, CONCRETE, CALCITE, DRIPSTONE, DEEPSLATE, OBSIDIAN,
        ANVIL, HONEY, SLIME, SPONGE, MUSHROOM, BASALT, PACKED_ICE,
        TINTED_GLASS, COPPER, UNKNOWN
    }

    private PBRMaterialDetector() {}

    public static BlockMaterialType detect(ResourceLocation location) {
        String path = location.getPath().toLowerCase();
        return detectFromPath(path);
    }

    public static BlockMaterialType detect(String namespace, String path) {
        return detectFromPath(path.toLowerCase());
    }

    private static BlockMaterialType detectFromPath(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);

        if (name.contains("deepslate")) return BlockMaterialType.DEEPSLATE;
        if (name.contains("end_stone") || name.contains("endstone")) return BlockMaterialType.ENDSTONE;
        if (name.contains("obsidian")) return BlockMaterialType.OBSIDIAN;
        if (name.contains("basalt")) return BlockMaterialType.BASALT;
        if (name.contains("calcite")) return BlockMaterialType.CALCITE;
        if (name.contains("dripstone")) return BlockMaterialType.DRIPSTONE;
        if (name.contains("netherrack") || name.contains("nether_") || name.contains("soul_")) return BlockMaterialType.NETHER;
        if (name.contains("bone") || name.contains("skull")) return BlockMaterialType.BONE;
        if (name.contains("mud")) return BlockMaterialType.MUD;
        if (name.contains("concrete")) return BlockMaterialType.CONCRETE;

        if (name.contains("anvil")) return BlockMaterialType.ANVIL;
        if (name.contains("honey")) return BlockMaterialType.HONEY;
        if (name.contains("slime")) return BlockMaterialType.SLIME;
        if (name.contains("sponge")) return BlockMaterialType.SPONGE;
        if (name.contains("mushroom")) return BlockMaterialType.MUSHROOM;

        if (name.contains("copper_")) return BlockMaterialType.COPPER;
        if (name.contains("iron_") || name.contains("gold_") || name.contains("_ore")) return BlockMaterialType.ORE;
        if (name.contains("emerald") || name.contains("diamond") || name.contains("lapis")
            || name.contains("amethyst") || name.contains("prismarine")) return BlockMaterialType.GEM;

        if (name.contains("packed_ice") || name.contains("blue_ice")) return BlockMaterialType.PACKED_ICE;
        if (name.contains("ice")) return BlockMaterialType.ICE;
        if (name.contains("snow")) return BlockMaterialType.SNOW;

        if (name.contains("tinted_glass")) return BlockMaterialType.TINTED_GLASS;
        if (name.startsWith("glass") || name.endsWith("_glass")) return BlockMaterialType.GLASS;

        if (name.contains("wool")) return BlockMaterialType.WOOL;
        if (name.contains("terracotta")) return BlockMaterialType.TERRACOTTA;

        if (name.contains("leaves")) return BlockMaterialType.LEAVES;
        if (name.contains("water")) return BlockMaterialType.WATER;
        if (name.contains("sand") && !name.contains("stone")) return BlockMaterialType.SAND;
        if (name.contains("gravel")) return BlockMaterialType.GRAVEL;

        if (name.contains("dirt") || name.contains("podzol") || name.contains("mycelium")
            || name.contains("grass_block") || name.contains("path")) return BlockMaterialType.DIRT;

        if (name.contains("log") || name.contains("_wood") || name.startsWith("wood")
            || name.contains("planks") || name.startsWith("oak") || name.startsWith("spruce")
            || name.startsWith("birch") || name.startsWith("jungle") || name.startsWith("acacia")
            || name.startsWith("cherry") || name.startsWith("mangrove") || name.startsWith("bamboo")
            || name.contains("_door") || name.contains("_trapdoor") || name.contains("_fence")
            || name.contains("_sign") || name.contains("_button") || name.contains("_pressure_plate")
            || name.contains("bookshelf") || name.contains("crafting") || name.contains("composter")
            || name.contains("_stem") || name.contains("_hyphae") || name.contains("_wart")) return BlockMaterialType.WOOD;

        if (name.contains("farmland") || name.contains("crop") || name.contains("flower")
            || name.contains("sapling") || name.contains("_plant") || name.contains("vine")
            || name.contains("tulip") || name.contains("lily") || name.contains("cactus")
            || name.contains("sugar_cane") || name.contains("pumpkin") || name.contains("melon")
            || name.contains("cocoa") || name.contains("kelp") || name.contains("grass")
            && !name.contains("grass_block")) return BlockMaterialType.PLANT;

        if (name.contains("stone") || name.contains("cobble") || name.contains("brick")
            || name.contains("andesite") || name.contains("diorite") || name.contains("granite")
            || name.contains("tuff") || name.contains("smooth_") || name.contains("chiseled_")
            || name.contains("cut_") || name.contains("polished_") || name.contains("pillar_")
            || name.contains("mossy") || name.contains("cracked_") || name.contains("prismarine")
            || name.contains("purpur") || name.contains("blackstone")) return BlockMaterialType.STONE;

        return BlockMaterialType.UNKNOWN;
    }
}
