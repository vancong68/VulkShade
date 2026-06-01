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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class PBRTextureManager {
    private static final String SPECULAR_SUFFIX = "_s";
    private static final String NORMAL_SUFFIX = "_n";

    public static final PBRTextureManager INSTANCE = new PBRTextureManager();

    private final Map<ResourceLocation, AbstractTexture> specularCache = new HashMap<>();
    private final Map<ResourceLocation, AbstractTexture> normalCache = new HashMap<>();

    private @Nullable DynamicTexture defaultSpecularTexture;
    private @Nullable DynamicTexture defaultNormalTexture;
    private boolean dirty = true;

    private PBRTextureManager() {
    }

    public void tick() {
    }

    public void markDirty() {
        clearCache();
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
            loc -> loadPBRTexture(loc, SPECULAR_SUFFIX));
        return tex.getTextureView();
    }

    public GpuTextureView getNormalTextureView(@Nullable ResourceLocation baseTextureLocation) {
        ensureReady();
        if (baseTextureLocation == null) return getDefaultNormalTexture().getTextureView();
        AbstractTexture tex = this.normalCache.computeIfAbsent(baseTextureLocation,
            loc -> loadPBRTexture(loc, NORMAL_SUFFIX));
        return tex.getTextureView();
    }

    private void ensureReady() {
        if (this.defaultSpecularTexture == null) {
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            img.setPixel(0, 0, packRGBA(0, 229, 0, 255));
            this.defaultSpecularTexture = new DynamicTexture(() -> "vulkanmod_pbr_specular_default", img);
            this.defaultSpecularTexture.upload();
        }
        if (this.defaultNormalTexture == null) {
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            img.setPixel(0, 0, packRGBA(128, 128, 255, 255));
            this.defaultNormalTexture = new DynamicTexture(() -> "vulkanmod_pbr_normal_default", img);
            this.defaultNormalTexture.upload();
        }
        this.dirty = false;
    }

    private AbstractTexture loadPBRTexture(ResourceLocation baseTextureLocation, String suffix) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        AbstractTexture baseTexture = Minecraft.getInstance().getTextureManager().getTexture(baseTextureLocation);

        if (baseTexture instanceof TextureAtlas atlas) {
            AbstractTexture atlasTex = loadAtlasPBRTexture(atlas, rm, suffix);
            if (atlasTex != null) return atlasTex;
        } else {
            AbstractTexture simpleTex = loadSimplePBRTexture(baseTextureLocation, rm, suffix);
            if (simpleTex != null) return simpleTex;
        }

        return suffix.equals(SPECULAR_SUFFIX) ? getDefaultSpecularTexture() : getDefaultNormalTexture();
    }

    private @Nullable AbstractTexture loadSimplePBRTexture(ResourceLocation baseLocation,
                                                            ResourceManager rm, String suffix) {
        ResourceLocation texLocation = appendSuffix(baseLocation, suffix);
        if (!resourceExists(rm, texLocation)) return null;
        SimpleTexture texture = new SimpleTexture(texLocation);
        try {
            texture.apply(texture.loadContents(rm));
            return texture;
        } catch (IOException e) {
            texture.close();
            return null;
        }
    }

    private @Nullable AbstractTexture loadAtlasPBRTexture(TextureAtlas atlas,
                                                           ResourceManager rm, String suffix) {
        TextureAtlasAccessor accessor = (TextureAtlasAccessor) atlas;
        int atlasWidth = accessor.callGetWidth();
        int atlasHeight = accessor.callGetHeight();
        NativeImage atlasImage = new NativeImage(NativeImage.Format.RGBA, atlasWidth, atlasHeight, false);
        atlasImage.fillRect(0, 0, atlasWidth, atlasHeight, 0x00000000);

        boolean foundAny = false;
        for (TextureAtlasSprite sprite : accessor.getTexturesByName().values()) {
            if (sprite == atlas.missingSprite()) continue;
            NativeImage frame = loadSpritePBRFrame(accessor, sprite, rm, suffix);
            if (frame == null) continue;
            foundAny = true;
            copyIntoAtlas(atlasImage, frame, sprite.getX(), sprite.getY());
            frame.close();
        }

        if (!foundAny) {
            atlasImage.close();
            return null;
        }

        String name = suffix.equals(SPECULAR_SUFFIX) ? "pbr_specular" : "pbr_normal";
        DynamicTexture result = new DynamicTexture(() -> "vulkanmod_" + name + "_atlas_" + atlas.location(), atlasImage);
        result.upload();
        return result;
    }

    private @Nullable NativeImage loadSpritePBRFrame(TextureAtlasAccessor accessor, TextureAtlasSprite sprite,
                                                      ResourceManager rm, String suffix) {
        ResourceLocation spriteLocation = sprite.contents().name();
        ResourceLocation imageLocation = spriteLocation.withPrefix("textures/").withSuffix(".png");
        ResourceLocation texLocation = appendSuffix(imageLocation, suffix);
        Resource resource = rm.getResource(texLocation).orElse(null);
        if (resource == null) return null;

        try (InputStream stream = resource.open()) {
            NativeImage image = NativeImage.read(stream);
            Optional<AnimationMetadataSection> animMeta = resource.metadata()
                .getSection(AnimationMetadataSection.TYPE);
            FrameSize frameSize = animMeta
                .map(meta -> meta.calculateFrameSize(image.getWidth(), image.getHeight()))
                .orElseGet(() -> new FrameSize(image.getWidth(), image.getHeight()));

            int frameWidth = frameSize.width();
            int frameHeight = frameSize.height();
            int targetWidth = sprite.contents().width();
            int targetHeight = sprite.contents().height();

            NativeImage frame = copyFrame(image, frameWidth, frameHeight);
            image.close();

            if (frameWidth != targetWidth || frameHeight != targetHeight) {
                NativeImage scaled = new NativeImage(NativeImage.Format.RGBA, targetWidth, targetHeight, false);
                frame.resizeSubRectTo(0, 0, frameWidth, frameHeight, scaled);
                frame.close();
                frame = scaled;
            }

            return frame;
        } catch (FileNotFoundException ignored) {
            return null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static NativeImage copyFrame(NativeImage source, int frameWidth, int frameHeight) {
        NativeImage frame = new NativeImage(NativeImage.Format.RGBA, frameWidth, frameHeight, false);
        for (int y = 0; y < frameHeight; ++y)
            for (int x = 0; x < frameWidth; ++x)
                frame.setPixel(x, y, source.getPixel(x, y));
        return frame;
    }

    private static void copyIntoAtlas(NativeImage atlas, NativeImage frame, int dstX, int dstY) {
        for (int y = 0; y < frame.getHeight(); ++y)
            for (int x = 0; x < frame.getWidth(); ++x)
                atlas.setPixel(dstX + x, dstY + y, frame.getPixel(x, y));
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
    }

    private DynamicTexture getDefaultSpecularTexture() { ensureReady(); return this.defaultSpecularTexture; }
    private DynamicTexture getDefaultNormalTexture() { ensureReady(); return this.defaultNormalTexture; }

    private static boolean resourceExists(ResourceManager rm, ResourceLocation loc) {
        return rm.getResource(loc).isPresent();
    }

    private static ResourceLocation appendSuffix(ResourceLocation location, String suffix) {
        String path = location.getPath();
        int extIdx = path.lastIndexOf('.');
        String updated = extIdx >= 0
            ? path.substring(0, extIdx) + suffix + path.substring(extIdx)
            : path + suffix;
        return ResourceLocation.fromNamespaceAndPath(location.getNamespace(), updated);
    }

    private static int packRGBA(int r, int g, int b, int a) {
        return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
    }
}
