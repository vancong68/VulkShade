package net.vulkanmod.render.pbr.loader;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.vulkanmod.mixin.texture.emissive.TextureAtlasAccessor;
import net.vulkanmod.render.pbr.PBRType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

public class AtlasPBRLoader implements PBRTextureLoader<TextureAtlas> {
    private static final Logger LOGGER = LogManager.getLogger("PBR-AtlasLoader");

    @Override
    public void load(TextureAtlas atlas, ResourceLocation location, ResourceManager resourceManager, PBRTextureConsumer consumer) {
        TextureAtlasAccessor accessor = (TextureAtlasAccessor) atlas;
        int atlasWidth = accessor.callGetWidth();
        int atlasHeight = accessor.callGetHeight();

        if (atlasWidth <= 0 || atlasHeight <= 0) return;

        Map<PBRType, StitchedAtlas> atlases = new EnumMap<>(PBRType.class);

        for (TextureAtlasSprite sprite : accessor.getTexturesByName().values()) {
            ResourceLocation spriteName = sprite.contents().name();
            if (spriteName == null || sprite == atlas.missingSprite()) continue;

            for (PBRType type : PBRType.values()) {
                NativeImage frame = loadSpriteFrame(spriteName, resourceManager, type);
                if (frame == null) continue;

                StitchedAtlas stitched = atlases.get(type);
                if (stitched == null) {
                    NativeImage atlasImage = new NativeImage(NativeImage.Format.RGBA, atlasWidth, atlasHeight, false);
                    atlasImage.fillRect(0, 0, atlasWidth, atlasHeight, type.getDefaultValue());
                    stitched = new StitchedAtlas(atlasImage, true);
                    atlases.put(type, stitched);
                }

                int frameWidth = frame.getWidth();
                int frameHeight = frame.getHeight();
                int targetWidth = sprite.contents().width();
                int targetHeight = sprite.contents().height();

                if (frameWidth != targetWidth || frameHeight != targetHeight) {
                    NativeImage scaled = new NativeImage(NativeImage.Format.RGBA, targetWidth, targetHeight, false);
                    frame.resizeSubRectTo(0, 0, frameWidth, frameHeight, scaled);
                    frame.close();
                    frame = scaled;
                }

                copyIntoAtlas(stitched.image, frame, sprite.getX(), sprite.getY());
                frame.close();
            }
        }

        for (Map.Entry<PBRType, StitchedAtlas> entry : atlases.entrySet()) {
            StitchedAtlas stitched = entry.getValue();
            if (!stitched.foundAny) continue;

            DynamicTexture tex = new DynamicTexture(
                    () -> "pbr_atlas_" + entry.getKey().getSuffix() + "_" + location,
                    stitched.image);
            tex.upload();

            switch (entry.getKey()) {
                case NORMAL -> consumer.acceptNormalTexture(tex);
                case SPECULAR -> consumer.acceptSpecularTexture(tex);
                case HEIGHT -> consumer.acceptHeightTexture(tex);
                case AO -> consumer.acceptAoTexture(tex);
            }
        }
    }

    private NativeImage loadSpriteFrame(ResourceLocation spriteName, ResourceManager rm, PBRType type) {
        String path = type.appendSuffix(spriteName.getPath());
        ResourceLocation pbrLocation = ResourceLocation.fromNamespaceAndPath(
                spriteName.getNamespace(), "textures/" + path + ".png");

        var optionalResource = rm.getResource(pbrLocation);
        if (optionalResource.isEmpty()) return null;

        try (InputStream stream = optionalResource.get().open()) {
            return NativeImage.read(stream);
        } catch (IOException e) {
            return null;
        }
    }

    private static void copyIntoAtlas(NativeImage atlasImage, NativeImage frame, int dstX, int dstY) {
        for (int y = 0; y < frame.getHeight(); ++y) {
            for (int x = 0; x < frame.getWidth(); ++x) {
                atlasImage.setPixel(dstX + x, dstY + y, frame.getPixel(x, y));
            }
        }
    }

    private record StitchedAtlas(NativeImage image, boolean foundAny) {
        StitchedAtlas(NativeImage image, boolean foundAny) {
            this.image = image;
            this.foundAny = true;
        }
    }
}
