package net.vulkanmod.render.texture.pbr;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import static net.vulkanmod.render.texture.pbr.PBRMaterialDetector.BlockMaterialType;

public final class PBRMaterialRegistry {
    private static final Logger LOGGER = LogManager.getLogger("PBR-MaterialRegistry");

    private static PBRMaterialRegistry INSTANCE;

    private final Object2ObjectOpenHashMap<String, PBRMaterial> materialCache = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<String, BlockMaterialType> typeCache = new Object2ObjectOpenHashMap<>();

    private PBRMaterialRegistry() {}

    public static PBRMaterialRegistry getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PBRMaterialRegistry();
        }
        return INSTANCE;
    }

    public PBRMaterial getOrDetect(ResourceLocation blockTextureLocation) {
        return getOrDetect(blockTextureLocation, null);
    }

    public PBRMaterial getOrDetect(ResourceLocation blockTextureLocation, @Nullable NativeImage textureImage) {
        if (blockTextureLocation == null) return PBRMaterial.DEFAULT;
        String key = blockTextureLocation.toString();
        PBRMaterial cached = materialCache.get(key);
        if (cached != null) return cached;

        BlockMaterialType type = typeCache.get(key);
        if (type == null) {
            type = PBRMaterialDetector.detect(blockTextureLocation, textureImage);
            typeCache.put(key, type);
        }

        PBRMaterial material = PBRFallbackGenerator.generate(type);
        if (textureImage != null) {
            material = PBRFallbackGenerator.enrichFromTexture(material, textureImage, type);
        }
        materialCache.put(key, material);
        LOGGER.debug("Generated fallback PBR material for {}: type={}, roughness={}",
            key, type, material.roughness);
        return material;
    }

    public void cache(ResourceLocation blockTextureLocation, PBRMaterial material) {
        if (blockTextureLocation == null) return;
        materialCache.put(blockTextureLocation.toString(), material);
    }

    public void invalidate(String key) {
        materialCache.remove(key);
        typeCache.remove(key);
    }

    public void clear() {
        materialCache.clear();
        typeCache.clear();
    }
}
