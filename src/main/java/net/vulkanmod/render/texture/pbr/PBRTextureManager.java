package net.vulkanmod.render.texture.pbr;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.vulkanmod.mixin.texture.emissive.TextureAtlasAccessor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public final class PBRTextureManager {
    /**
     * Integrated material atlas generator.
     *
     * This manager no longer relies on external PBR texture pack files.
     * Specular and normal atlases are generated from vanilla/mod texture data.
     */
    public static final PBRTextureManager INSTANCE = new PBRTextureManager();

    private final Map<ResourceLocation, AbstractTexture> specularCache = new HashMap<>();
    private final Map<ResourceLocation, AbstractTexture> normalCache = new HashMap<>();

    private @Nullable DynamicTexture defaultSpecularTexture;
    private @Nullable DynamicTexture defaultNormalTexture;
    private boolean dirty = true;

    private final PBRMaterialRegistry materialRegistry = PBRMaterialRegistry.getInstance();

    private PBRTextureManager() {}

    public void tick() {}

    public void markDirty() {
        clearCache();
        this.materialRegistry.clear();
        this.dirty = true;
    }

    public void close() {
        clearCache();
        if (this.defaultSpecularTexture != null) {
            this.defaultSpecularTexture.close();
            this.defaultSpecularTexture = null;
        }
        if (this.defaultNormalTexture != null) {
            this.defaultNormalTexture.close();
            this.defaultNormalTexture = null;
        }
    }

    public GpuTextureView getSpecularTextureView(@Nullable ResourceLocation baseTextureLocation) {
        ensureReady();
        if (baseTextureLocation == null) return getDefaultSpecularTexture().getTextureView();
        AbstractTexture tex = this.specularCache.computeIfAbsent(baseTextureLocation,
            loc -> loadPBRTexture(loc, false));
        return tex.getTextureView();
    }

    public GpuTextureView getNormalTextureView(@Nullable ResourceLocation baseTextureLocation) {
        ensureReady();
        if (baseTextureLocation == null) return getDefaultNormalTexture().getTextureView();
        AbstractTexture tex = this.normalCache.computeIfAbsent(baseTextureLocation,
            loc -> loadPBRTexture(loc, true));
        return tex.getTextureView();
    }

    private void ensureReady() {
        if (this.defaultSpecularTexture == null) {
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            img.setPixel(0, 0, 0x00000000);
            this.defaultSpecularTexture = new DynamicTexture(() -> "vulkanmod_pbr_specular_default", img);
            this.defaultSpecularTexture.upload();
        }
        if (this.defaultNormalTexture == null) {
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            img.setPixel(0, 0, PBRFallbackGenerator.encodeFallbackNormal(1.0f));
            this.defaultNormalTexture = new DynamicTexture(() -> "vulkanmod_pbr_normal_default", img);
            this.defaultNormalTexture.upload();
        }

        if (this.dirty) {
            this.dirty = false;
        }
    }

    private AbstractTexture loadPBRTexture(ResourceLocation baseTextureLocation, boolean isNormal) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        AbstractTexture baseTexture = Minecraft.getInstance().getTextureManager().getTexture(baseTextureLocation);

        if (baseTexture instanceof TextureAtlas atlas) {
            AbstractTexture atlasTex = loadAtlasPBRTexture(atlas, rm, isNormal);
            if (atlasTex != null) return atlasTex;
        } else {
            AbstractTexture simpleTex = loadSimplePBRTexture(baseTextureLocation, rm, isNormal);
            if (simpleTex != null) return simpleTex;
        }

        return isNormal ? getDefaultNormalTexture() : getDefaultSpecularTexture();
    }

    private @Nullable AbstractTexture loadSimplePBRTexture(ResourceLocation baseLocation,
                                                            ResourceManager rm, boolean isNormal) {
        return generateSimpleFallbackTexture(baseLocation, rm, isNormal);
    }

    private AbstractTexture generateSimpleFallbackTexture(ResourceLocation baseLocation, ResourceManager rm, boolean isNormal) {
        NativeImage sourceImage = loadBaseTextureImage(rm, baseLocation);
        PBRMaterial material = this.materialRegistry.getOrDetect(baseLocation, sourceImage);

        if (isNormal) {
            NativeImage image = sourceImage != null
                ? PBRFallbackGenerator.generateProceduralNormal(sourceImage, material.ao)
                : new NativeImage(NativeImage.Format.RGBA, 1, 1, false);

            if (sourceImage == null) {
                image.setPixel(0, 0, PBRFallbackGenerator.encodeFallbackNormal(material.ao));
            }

            DynamicTexture texture = new DynamicTexture(() -> "vulkanmod_pbr_normal_fallback_" + baseLocation, image);
            texture.upload();
            if (sourceImage != null) sourceImage.close();
            return texture;
        }

        int encoded = PBRFallbackGenerator.encodeFallbackSpecular(material, 0.5f);
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        image.setPixel(0, 0, encoded);
        DynamicTexture texture = new DynamicTexture(() -> "vulkanmod_pbr_specular_fallback_" + baseLocation, image);
        texture.upload();
        if (sourceImage != null) sourceImage.close();
        return texture;
    }

    private @Nullable AbstractTexture loadAtlasPBRTexture(TextureAtlas atlas, ResourceManager rm, boolean isNormal) {
        TextureAtlasAccessor accessor = (TextureAtlasAccessor) atlas;
        int atlasWidth = accessor.callGetWidth();
        int atlasHeight = accessor.callGetHeight();
        NativeImage atlasImage = new NativeImage(NativeImage.Format.RGBA, atlasWidth, atlasHeight, false);
        atlasImage.fillRect(0, 0, atlasWidth, atlasHeight, 0x00000000);

        for (TextureAtlasSprite sprite : accessor.getTexturesByName().values()) {
            if (sprite == atlas.missingSprite()) continue;
            NativeImage frame = generateFallbackFrame(sprite, rm, isNormal);
            copyIntoAtlas(atlasImage, frame, sprite.getX(), sprite.getY());
            frame.close();
        }

        String name = isNormal ? "pbr_normal" : "pbr_specular";
        DynamicTexture result = new DynamicTexture(() -> "vulkanmod_" + name + "_atlas_" + atlas.location(), atlasImage);
        result.upload();
        return result;
    }

    private NativeImage generateFallbackFrame(TextureAtlasSprite sprite, ResourceManager rm, boolean isNormal) {
        ResourceLocation spriteLocation = sprite.contents().name();
        NativeImage sourceImage = loadBaseTextureImage(rm, spriteLocation);
        PBRMaterial material = this.materialRegistry.getOrDetect(spriteLocation, sourceImage);

        int targetWidth = sprite.contents().width();
        int targetHeight = sprite.contents().height();

        NativeImage frame;
        if (isNormal) {
            if (sourceImage != null) {
                frame = PBRFallbackGenerator.generateProceduralNormal(sourceImage, material.ao);
            } else {
                frame = new NativeImage(NativeImage.Format.RGBA, targetWidth, targetHeight, false);
                int encoded = PBRFallbackGenerator.encodeFallbackNormal(material.ao);
                for (int y = 0; y < targetHeight; ++y)
                    for (int x = 0; x < targetWidth; ++x)
                        frame.setPixel(x, y, encoded);
            }
        } else {
            long seed = spriteLocation.toString().hashCode();
            float variation = (float)(((seed * 0x9E3779B97F4A7C15L) & 0xFFFFFFFFL) % 256L) / 256.0f;
            int encoded = PBRFallbackGenerator.encodeFallbackSpecular(material, variation);
            frame = new NativeImage(NativeImage.Format.RGBA, targetWidth, targetHeight, false);
            for (int y = 0; y < targetHeight; ++y)
                for (int x = 0; x < targetWidth; ++x)
                    frame.setPixel(x, y, encoded);
        }

        if (sourceImage != null) {
            sourceImage.close();
        }
        return frame;
    }


    private @Nullable NativeImage loadBaseTextureImage(ResourceManager rm, ResourceLocation textureLocation) {
        ResourceLocation imageLocation = getTextureFileLocation(textureLocation);
        Resource resource = rm.getResource(imageLocation).orElse(null);
        if (resource == null) return null;

        try (InputStream stream = resource.open()) {
            return NativeImage.read(stream);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static ResourceLocation getTextureFileLocation(ResourceLocation textureLocation) {
        String path = textureLocation.getPath();
        if (path.startsWith("textures/")) {
            path = path.substring("textures/".length());
        }
        if (!path.endsWith(".png")) {
            path = path + ".png";
        }
        return ResourceLocation.fromNamespaceAndPath(textureLocation.getNamespace(), "textures/" + path);
    }

    private static NativeImage copyFrame(NativeImage source, int frameWidth, int frameHeight) {
        NativeImage frame = new NativeImage(NativeImage.Format.RGBA, frameWidth, frameHeight, false);
        for (int y = 0; y < frameHeight; ++y)
            for (int x = 0; x < frameWidth; ++x)
                frame.setPixel(x, y, source.getPixel(x, y));
        return frame;
    }

    private static void copyIntoAtlas(NativeImage atlas, NativeImage frame, int dstX, int dstY) {
        int atlasW = atlas.getWidth();
        int atlasH = atlas.getHeight();
        int frameW = frame.getWidth();
        int frameH = frame.getHeight();

        int srcStartX = 0;
        int srcStartY = 0;
        int dstStartX = dstX;
        int dstStartY = dstY;

        if (dstStartX < 0) { srcStartX = -dstStartX; dstStartX = 0; }
        if (dstStartY < 0) { srcStartY = -dstStartY; dstStartY = 0; }

        int copyW = Math.min(frameW - srcStartX, atlasW - dstStartX);
        int copyH = Math.min(frameH - srcStartY, atlasH - dstStartY);

        if (copyW <= 0 || copyH <= 0) return;

        for (int y = 0; y < copyH; ++y) {
            int srcY = srcStartY + y;
            int dstYPos = dstStartY + y;
            for (int x = 0; x < copyW; ++x) {
                int srcX = srcStartX + x;
                int dstXPos = dstStartX + x;
                atlas.setPixel(dstXPos, dstYPos, frame.getPixel(srcX, srcY));
            }
        }
    }

    private void clearCache() {
        for (AbstractTexture tex : specularCache.values()) {
            if (tex != defaultSpecularTexture) try { tex.close(); } catch (Exception ignored) {}
        }
        for (AbstractTexture tex : normalCache.values()) {
            if (tex != defaultNormalTexture) try { tex.close(); } catch (Exception ignored) {}
        }
        specularCache.clear();
        normalCache.clear();
        materialRegistry.clear();
    }

    private DynamicTexture getDefaultSpecularTexture() { ensureReady(); return this.defaultSpecularTexture; }
    private DynamicTexture getDefaultNormalTexture() { ensureReady(); return this.defaultNormalTexture; }

}
