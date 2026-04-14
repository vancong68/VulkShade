package net.vulkanmod.render.texture.emissive;

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
import java.util.Properties;

public final class EmissiveTextureManager {
    private static final ResourceLocation EMISSIVE_PROPERTIES_LOCATION =
            ResourceLocation.fromNamespaceAndPath("minecraft", "optifine/emissive.properties");
    private static final String DEFAULT_SUFFIX = "_e";

    public static final EmissiveTextureManager INSTANCE = new EmissiveTextureManager();

    private final Map<ResourceLocation, AbstractTexture> cache = new HashMap<>();

    private @Nullable DynamicTexture defaultBlackTexture;
    private String emissiveSuffix = DEFAULT_SUFFIX;
    private boolean dirty = true;

    private EmissiveTextureManager() {
    }

    public void tick() {
        // Static emissive textures only for now. Atlas/frame animation can be
        // added later once the base path is confirmed working.
    }

    public void markDirty() {
        this.clearCache();
        this.dirty = true;
    }

    public void close() {
        this.clearCache();
        if (this.defaultBlackTexture != null) {
            this.defaultBlackTexture.close();
            this.defaultBlackTexture = null;
        }
    }

    public GpuTextureView getEmissiveTextureView(@Nullable ResourceLocation baseTextureLocation) {
        this.ensureReady();

        if (baseTextureLocation == null) {
            return this.getDefaultBlackTexture().getTextureView();
        }

        AbstractTexture emissiveTexture = this.cache.computeIfAbsent(baseTextureLocation, this::loadEmissiveTexture);
        return emissiveTexture.getTextureView();
    }

    private void ensureReady() {
        if (this.defaultBlackTexture == null) {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            image.fillRect(0, 0, 1, 1, 0x00000000);
            this.defaultBlackTexture = new DynamicTexture(() -> "vulkanmod_emissive_default", image);
            this.defaultBlackTexture.upload();
        }

        if (!this.dirty) {
            return;
        }

        this.emissiveSuffix = DEFAULT_SUFFIX;

        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        Resource resource = resourceManager.getResource(EMISSIVE_PROPERTIES_LOCATION).orElse(null);
        if (resource != null) {
            Properties properties = new Properties();
            try (InputStream stream = resource.open()) {
                properties.load(stream);

                String parsedSuffix = properties.getProperty("suffix.emissive");
                if (parsedSuffix != null && !parsedSuffix.isBlank()) {
                    this.emissiveSuffix = parsedSuffix.trim();
                }
            } catch (FileNotFoundException ignored) {
            } catch (IOException ignored) {
            }
        }

        this.dirty = false;
    }

    private AbstractTexture loadEmissiveTexture(ResourceLocation baseTextureLocation) {
        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        AbstractTexture baseTexture = Minecraft.getInstance().getTextureManager().getTexture(baseTextureLocation);

        if (baseTexture instanceof TextureAtlas atlasTexture) {
            AbstractTexture atlasEmissive = this.loadAtlasEmissiveTexture(atlasTexture, resourceManager);
            if (atlasEmissive != null) {
                return atlasEmissive;
            }
        } else {
            AbstractTexture simpleEmissive = this.loadSimpleEmissiveTexture(baseTextureLocation, resourceManager);
            if (simpleEmissive != null) {
                return simpleEmissive;
            }
        }

        return this.getDefaultBlackTexture();
    }

    private @Nullable AbstractTexture loadSimpleEmissiveTexture(ResourceLocation baseTextureLocation, ResourceManager resourceManager) {
        ResourceLocation emissiveLocation = appendSuffix(baseTextureLocation, this.emissiveSuffix);
        if (!resourceExists(resourceManager, emissiveLocation)) {
            return null;
        }

        SimpleTexture texture = new SimpleTexture(emissiveLocation);
        try {
            texture.apply(texture.loadContents(resourceManager));
            return texture;
        } catch (IOException e) {
            texture.close();
            return null;
        }
    }

    private @Nullable AbstractTexture loadAtlasEmissiveTexture(TextureAtlas atlasTexture, ResourceManager resourceManager) {
        TextureAtlasAccessor accessor = (TextureAtlasAccessor) atlasTexture;
        int atlasWidth = accessor.callGetWidth();
        int atlasHeight = accessor.callGetHeight();
        NativeImage atlasImage = new NativeImage(NativeImage.Format.RGBA, atlasWidth, atlasHeight, false);
        atlasImage.fillRect(0, 0, atlasWidth, atlasHeight, 0x00000000);

        boolean foundAny = false;
        for (TextureAtlasSprite sprite : accessor.getTexturesByName().values()) {
            if (sprite == atlasTexture.missingSprite()) {
                continue;
            }

            NativeImage emissiveFrame = this.loadSpriteEmissiveFrame(accessor, sprite, resourceManager);
            if (emissiveFrame == null) {
                continue;
            }

            foundAny = true;
            copyIntoAtlas(atlasImage, emissiveFrame, sprite.getX(), sprite.getY());
            emissiveFrame.close();
        }

        if (!foundAny) {
            atlasImage.close();
            return null;
        }

        DynamicTexture atlasEmissiveTexture = new DynamicTexture(
                () -> "vulkanmod_emissive_atlas_" + atlasTexture.location(),
                atlasImage
        );
        atlasEmissiveTexture.upload();
        return atlasEmissiveTexture;
    }

    private @Nullable NativeImage loadSpriteEmissiveFrame(TextureAtlasAccessor atlasAccessor, TextureAtlasSprite sprite, ResourceManager resourceManager) {
        ResourceLocation spriteLocation = sprite.contents().name();
        ResourceLocation imageLocation = spriteLocation.withPrefix("textures/").withSuffix(".png");
        ResourceLocation emissiveLocation = appendSuffix(imageLocation, this.emissiveSuffix);
        Resource resource = resourceManager.getResource(emissiveLocation).orElse(null);
        if (resource == null) {
            return null;
        }

        try (InputStream stream = resource.open()) {
            NativeImage image = NativeImage.read(stream);
            Optional<AnimationMetadataSection> animationMetadata = resource.metadata().getSection(AnimationMetadataSection.TYPE);
            FrameSize frameSize = animationMetadata
                    .map(metadata -> metadata.calculateFrameSize(image.getWidth(), image.getHeight()))
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

        for (int y = 0; y < frameHeight; ++y) {
            for (int x = 0; x < frameWidth; ++x) {
                frame.setPixel(x, y, source.getPixel(x, y));
            }
        }

        return frame;
    }

    private static void copyIntoAtlas(NativeImage atlasImage, NativeImage frame, int dstX, int dstY) {
        for (int y = 0; y < frame.getHeight(); ++y) {
            for (int x = 0; x < frame.getWidth(); ++x) {
                atlasImage.setPixel(dstX + x, dstY + y, frame.getPixel(x, y));
            }
        }
    }

    private void clearCache() {
        if (this.cache.isEmpty()) {
            return;
        }

        for (AbstractTexture texture : this.cache.values()) {
            if (texture == this.defaultBlackTexture) {
                continue;
            }

            try {
                texture.close();
            } catch (Exception ignored) {
            }
        }

        this.cache.clear();
    }

    private DynamicTexture getDefaultBlackTexture() {
        this.ensureReady();
        return this.defaultBlackTexture;
    }

    private static boolean resourceExists(ResourceManager resourceManager, ResourceLocation location) {
        return resourceManager.getResource(location).isPresent();
    }

    private static ResourceLocation appendSuffix(ResourceLocation location, String suffix) {
        String path = location.getPath();
        int extensionIndex = path.lastIndexOf('.');
        String updatedPath = extensionIndex >= 0
                ? path.substring(0, extensionIndex) + suffix + path.substring(extensionIndex)
                : path + suffix;
        return ResourceLocation.fromNamespaceAndPath(location.getNamespace(), updatedPath);
    }
}
