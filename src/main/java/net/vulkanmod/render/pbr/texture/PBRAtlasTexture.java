package net.vulkanmod.render.pbr.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.render.pbr.PBRType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

public class PBRAtlasTexture extends DynamicTexture {
    private static final Logger LOGGER = LogManager.getLogger("PBR-AtlasTexture");

    private final ResourceLocation baseAtlasLocation;
    private final PBRType pbrType;
    private final Map<ResourceLocation, int[]> spriteRegions = new HashMap<>();

    public PBRAtlasTexture(ResourceLocation baseAtlasLocation, PBRType pbrType) {
        super(() -> "pbr_atlas_" + pbrType.getSuffix() + "_" + baseAtlasLocation,
                new NativeImage(NativeImage.Format.RGBA, 1, 1, false));
        this.baseAtlasLocation = baseAtlasLocation;
        this.pbrType = pbrType;
    }

    public void addSpriteFrame(TextureAtlasSprite sprite, NativeImage frame) {
        ResourceLocation name = sprite.contents().name();
        spriteRegions.put(name, new int[]{sprite.getX(), sprite.getY(), frame.getWidth(), frame.getHeight()});
    }

    public PBRType getPBRType() {
        return pbrType;
    }

    public ResourceLocation getBaseAtlasLocation() {
        return baseAtlasLocation;
    }

    public boolean hasSprite(ResourceLocation spriteName) {
        return spriteRegions.containsKey(spriteName);
    }
}
