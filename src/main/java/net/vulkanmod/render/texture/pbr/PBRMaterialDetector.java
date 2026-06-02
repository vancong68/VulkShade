package net.vulkanmod.render.texture.pbr;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public final class PBRMaterialDetector {

    public enum BlockMaterialType {
        STONE, DIRT, WOOD, METAL, GLASS, WATER, LEAVES, SAND, GRAVEL,
        SNOW, ICE, WOOL, TERRACOTTA, PLANT, ORE, GEM, BONE, NETHER,
        ENDSTONE, MUD, CONCRETE, CALCITE, DRIPSTONE, DEEPSLATE, OBSIDIAN,
        ANVIL, HONEY, SLIME, SPONGE, MUSHROOM, BASALT, PACKED_ICE,
        TINTED_GLASS, COPPER, UNKNOWN
    }

    public enum MaterialClass {
        DEFAULT(0),
        ROCK(1),
        WOOD(2),
        METAL(3),
        GLASS(4),
        LEAF(5),
        ORGANIC(6),
        SAND(7),
        DIRT(8),
        WATER(9),
        ICE(10),
        EMISSIVE(11);

        private final int id;

        MaterialClass(int id) {
            this.id = id;
        }

        public int id() {
            return this.id;
        }
    }

    private PBRMaterialDetector() {}

    public static BlockMaterialType detect(ResourceLocation location) {
        String path = location.getPath().toLowerCase();
        return detectFromPath(path);
    }

    public static BlockMaterialType detect(ResourceLocation location, @Nullable NativeImage textureImage) {
        BlockMaterialType type = detect(location);
        if (type != BlockMaterialType.UNKNOWN || textureImage == null) {
            return type;
        }
        return inferFromTexture(textureImage);
    }

    public static MaterialClass detectMaterialClass(ResourceLocation location) {
        return toMaterialClass(detect(location));
    }

    public static MaterialClass detectMaterialClass(ResourceLocation location, @Nullable NativeImage textureImage) {
        return toMaterialClass(detect(location, textureImage));
    }

    public static MaterialClass detectMaterialClass(String namespace, String path) {
        return toMaterialClass(detect(namespace, path));
    }

    public static BlockMaterialType detect(String namespace, String path) {
        return detectFromPath(path.toLowerCase());
    }

    public static BlockMaterialType inferFromTexture(NativeImage image) {
        float[] hsl = computeAverageHSL(image);
        float luminance = hsl[2];
        float saturation = hsl[1];
        float hue = hsl[0];
        float contrast = computeContrast(image);

        if (luminance > 0.85f && saturation < 0.12f) return BlockMaterialType.ICE;
        if (luminance > 0.72f && saturation < 0.20f) return BlockMaterialType.SNOW;
        if (contrast > 0.28f && luminance < 0.35f) return BlockMaterialType.ORE;
        if (saturation < 0.18f && luminance < 0.60f) return BlockMaterialType.DIRT;
        if (hue >= 0.05f && hue <= 0.16f && saturation > 0.22f) return BlockMaterialType.SAND;
        if (hue >= 0.06f && hue <= 0.16f && saturation > 0.35f && luminance > 0.45f) return BlockMaterialType.WOOD;
        if (hue >= 0.18f && hue <= 0.45f && saturation > 0.25f) return BlockMaterialType.PLANT;
        if (saturation > 0.40f && luminance > 0.35f) return BlockMaterialType.LEAVES;
        if (contrast > 0.30f && luminance > 0.65f) return BlockMaterialType.CONCRETE;
        if (saturation < 0.20f && luminance > 0.45f) return BlockMaterialType.STONE;
        return BlockMaterialType.UNKNOWN;
    }

    private static float[] computeAverageHSL(NativeImage image) {
        long redSum = 0;
        long greenSum = 0;
        long blueSum = 0;
        int width = image.getWidth();
        int height = image.getHeight();
        int total = width * height;

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int color = image.getPixel(x, y);
                redSum += (color >> 0) & 0xFF;
                greenSum += (color >> 8) & 0xFF;
                blueSum += (color >> 16) & 0xFF;
            }
        }

        float r = redSum / (float)(total * 255);
        float g = greenSum / (float)(total * 255);
        float b = blueSum / (float)(total * 255);
        return rgbToHsl(r, g, b);
    }

    private static float computeContrast(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        float mean = 0.0f;
        float meanSq = 0.0f;
        int total = width * height;

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                float lum = computeLuminance(image.getPixel(x, y));
                mean += lum;
                meanSq += lum * lum;
            }
        }

        mean /= total;
        meanSq /= total;
        return clamp((float)Math.sqrt(Math.max(0.0f, meanSq - mean * mean)), 0.0f, 1.0f);
    }

    private static float computeLuminance(int color) {
        float r = ((color >> 0) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = ((color >> 16) & 0xFF) / 255.0f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float[] rgbToHsl(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float h = 0.0f;
        float s = 0.0f;
        float l = (max + min) * 0.5f;

        if (max != min) {
            float delta = max - min;
            s = l > 0.5f ? delta / (2.0f - max - min) : delta / (max + min);
            if (max == r) {
                h = (g - b) / delta + (g < b ? 6.0f : 0.0f);
            } else if (max == g) {
                h = (b - r) / delta + 2.0f;
            } else {
                h = (r - g) / delta + 4.0f;
            }
            h /= 6.0f;
        }

        return new float[]{h, s, l};
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : value > max ? max : value;
    }

    public static MaterialClass toMaterialClass(BlockMaterialType type) {
        return switch (type) {
            case WOOD -> MaterialClass.WOOD;
            case LEAVES -> MaterialClass.LEAF;
            case PLANT, MUSHROOM -> MaterialClass.ORGANIC;
            case SAND, GRAVEL -> MaterialClass.SAND;
            case DIRT, MUD, TERRACOTTA, SNOW, ENDSTONE -> MaterialClass.DIRT;
            case WATER -> MaterialClass.WATER;
            case ICE, PACKED_ICE -> MaterialClass.ICE;
            case GLASS, TINTED_GLASS -> MaterialClass.GLASS;
            case METAL, COPPER, ORE, GEM, ANVIL -> MaterialClass.METAL;
            case BONE, NETHER, CONCRETE, CALCITE, DRIPSTONE, DEEPSLATE, OBSIDIAN, BASALT -> MaterialClass.ROCK;
            case HONEY, SLIME, SPONGE -> MaterialClass.ORGANIC;
            case UNKNOWN -> MaterialClass.DEFAULT;
            default -> MaterialClass.ROCK;
        };
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
