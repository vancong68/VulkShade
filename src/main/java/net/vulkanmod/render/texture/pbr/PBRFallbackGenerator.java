package net.vulkanmod.render.texture.pbr;

import com.mojang.blaze3d.platform.NativeImage;

import static net.vulkanmod.render.texture.pbr.PBRMaterialDetector.BlockMaterialType;

public final class PBRFallbackGenerator {

    private PBRFallbackGenerator() {}

    public static PBRMaterial generate(BlockMaterialType type) {
        return switch (type) {
            case STONE     -> new PBRMaterial(0.85f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case DIRT      -> new PBRMaterial(0.95f, 0.0f,  0.04f, 0.9f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case WOOD      -> new PBRMaterial(0.68f, 0.0f,  0.04f, 0.8f, 0.0f, 0.0f, 0.0f, 0.2f, 0);
            case METAL     -> new PBRMaterial(0.20f, 1.0f,  0.50f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 255);
            case GLASS     -> new PBRMaterial(0.05f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case WATER     -> new PBRMaterial(0.01f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0);
            case LEAVES    -> new PBRMaterial(0.75f, 0.0f,  0.04f, 0.7f, 0.0f, 0.0f, 0.3f, 0.0f, 0);
            case SAND      -> new PBRMaterial(0.95f, 0.0f,  0.04f, 0.9f, 0.0f, 0.0f, 0.0f, 0.5f, 0);
            case GRAVEL    -> new PBRMaterial(0.95f, 0.0f,  0.04f, 0.9f, 0.0f, 0.0f, 0.0f, 0.5f, 0);
            case SNOW      -> new PBRMaterial(0.95f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case ICE       -> new PBRMaterial(0.00f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case WOOL      -> new PBRMaterial(0.95f, 0.0f,  0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0);
            case TERRACOTTA -> new PBRMaterial(0.90f, 0.0f, 0.04f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            case PLANT     -> new PBRMaterial(0.75f, 0.0f,  0.04f, 0.8f, 0.0f, 0.0f, 0.3f, 0.0f, 0);
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

    public static PBRMaterial enrichFromTexture(PBRMaterial material, NativeImage texture, BlockMaterialType type) {
        PBRMaterial result = material.copy();
        float brightness = computeAverageBrightness(texture);
        float contrast = computeContrast(texture);

        if (type == BlockMaterialType.UNKNOWN) {
            result.roughness = clamp(0.85f - contrast * 0.45f + (0.5f - brightness) * 0.15f, 0.06f, 0.95f);
            result.ao = clamp(0.65f + (1.0f - contrast) * 0.18f, 0.35f, 1.0f);
            if (brightness < 0.35f) result.metallic = clamp(result.metallic + 0.1f, 0.0f, 1.0f);
        } else {
            if (type == BlockMaterialType.WOOD || type == BlockMaterialType.PLANT || type == BlockMaterialType.LEAVES) {
                result.roughness = clamp(result.roughness + 0.06f - contrast * 0.1f, 0.25f, 0.99f);
                result.ao = clamp(result.ao + 0.05f, 0.35f, 1.0f);
            } else if (type == BlockMaterialType.METAL || type == BlockMaterialType.COPPER || type == BlockMaterialType.ORE) {
                result.roughness = clamp(result.roughness - contrast * 0.05f, 0.08f, 0.85f);
            } else if (type == BlockMaterialType.GLASS || type == BlockMaterialType.ICE) {
                result.roughness = clamp(result.roughness * 0.55f, 0.01f, 0.25f);
            }
        }

        result.roughness = clamp(result.roughness, 0.01f, 0.99f);
        result.ao = clamp(result.ao, 0.1f, 1.0f);
        return result;
    }

    public static NativeImage generateProceduralNormal(NativeImage source, float ao) {
        int width = source.getWidth();
        int height = source.getHeight();
        NativeImage normalMap = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                float left = sampleLuminance(source, x - 1, y);
                float right = sampleLuminance(source, x + 1, y);
                float up = sampleLuminance(source, x, y - 1);
                float down = sampleLuminance(source, x, y + 1);

                float dx = (right - left) * 0.5f;
                float dy = (down - up) * 0.5f;
                float nx = -dx;
                float ny = -dy;
                float nz = 1.0f;
                float len = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 0.0f) {
                    nx /= len;
                    ny /= len;
                }

                normalMap.setPixel(x, y, encodeNormal(nx, ny, nz, ao));
            }
        }

        return normalMap;
    }

    public static int encodeFallbackSpecular(PBRMaterial material, float variation) {
        float roughness = clampVaried(material.roughness, variation, 0.1f);
        float perceptualSmoothness = 1.0f - (float)Math.sqrt(roughness);

        float green;
        if (material.metallic > 0.5f && material.metalID >= 230 && material.metalID <= 237) {
            green = (material.metalID + variation - 0.5f) / 255.0f;
            green = clamp(green, 0.902f, 0.999f);
        } else if (material.metallic > 0.5f) {
            green = 0.95f + variation * 0.04f;
        } else {
            green = material.f0 * (0.04f / 0.902f);
            green = Math.min(green, 0.901f);
        }

        float blue;
        if (material.porosity > 0.01f) {
            blue = material.porosity * (1.0f - variation * 0.3f);
            blue = Math.min(blue, 0.25f);
        } else if (material.sss > 0.01f) {
            blue = 0.251f + material.sss * 0.749f * (0.8f + variation * 0.2f);
            blue = Math.min(blue, 1.0f);
        } else {
            blue = variation * 0.05f;
        }

        float alpha = material.emissive;
        return packRGBA(perceptualSmoothness, green, blue, alpha);
    }

    public static int encodeFallbackNormal(float ao) {
        return packRGBA(0.5f, 0.5f, ao, 0.0f);
    }

    public static float clampVaried(float base, float variation, float maxDelta) {
        return clamp(base + (variation - 0.5f) * 2.0f * maxDelta, 0.001f, 0.999f);
    }

    public static int encodeNormal(float x, float y, float z, float ao) {
        float r = x * 0.5f + 0.5f;
        float g = -y * 0.5f + 0.5f;
        return packRGBA(r, g, ao, 0.0f);
    }

    private static float computeAverageBrightness(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        float sum = 0.0f;
        int total = width * height;

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                sum += computeLuminance(image.getPixel(x, y));
            }
        }

        return total == 0 ? 0.0f : sum / total;
    }

    private static float computeContrast(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int total = width * height;
        float mean = 0.0f;
        float meanSq = 0.0f;

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

    private static float sampleLuminance(NativeImage image, int x, int y) {
        int clampedX = Math.min(Math.max(x, 0), image.getWidth() - 1);
        int clampedY = Math.min(Math.max(y, 0), image.getHeight() - 1);
        return computeLuminance(image.getPixel(clampedX, clampedY));
    }

    private static float computeLuminance(int color) {
        float r = ((color >> 0) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = ((color >> 16) & 0xFF) / 255.0f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float packChannel(float value) {
        return Math.min(1.0f, Math.max(0.0f, value));
    }

    private static int packRGBA(float r, float g, float b, float a) {
        return ((int)(packChannel(a) * 255.0f) << 24)
             | ((int)(packChannel(b) * 255.0f) << 16)
             | ((int)(packChannel(g) * 255.0f) << 8)
             |  (int)(packChannel(r) * 255.0f);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : value > max ? max : value;
    }
}
