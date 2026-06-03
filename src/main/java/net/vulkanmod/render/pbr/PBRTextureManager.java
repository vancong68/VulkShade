package net.vulkanmod.render.pbr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.vulkanmod.render.pbr.format.TextureFormatLoader;
import net.vulkanmod.render.pbr.loader.PBRTextureLoader;
import net.vulkanmod.render.pbr.loader.PBRTextureLoaderRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class PBRTextureManager {
    public static final PBRTextureManager INSTANCE = new PBRTextureManager();
    private static final Logger LOGGER = LogManager.getLogger("PBR-TextureManager");

    private final Map<String, PBRTextureHolder> holders = new HashMap<>();
    private final ConsumerImpl consumer = new ConsumerImpl();

    private DynamicTexture defaultNormalTexture;
    private DynamicTexture defaultSpecularTexture;
    private DynamicTexture defaultHeightTexture;
    private DynamicTexture defaultAoTexture;

    private final PBRTextureHolder defaultHolder = new PBRTextureHolder() {
        @Override public @NotNull AbstractTexture normalTexture() { return defaultNormalTexture; }
        @Override public @NotNull AbstractTexture specularTexture() { return defaultSpecularTexture; }
        @Override public @NotNull AbstractTexture heightTexture() { return defaultHeightTexture; }
        @Override public @NotNull AbstractTexture aoTexture() { return defaultAoTexture; }
    };

    private PBRTextureManager() {}

    public void init() {
        defaultNormalTexture = createDefaultTexture(PBRType.NORMAL.getDefaultValue());
        defaultSpecularTexture = createDefaultTexture(PBRType.SPECULAR.getDefaultValue());
        defaultHeightTexture = createDefaultTexture(PBRType.HEIGHT.getDefaultValue());
        defaultAoTexture = createDefaultTexture(PBRType.AO.getDefaultValue());
        TextureFormatLoader.reload();
    }

    private DynamicTexture createDefaultTexture(int rgba) {
        com.mojang.blaze3d.platform.NativeImage img =
                new com.mojang.blaze3d.platform.NativeImage(
                        com.mojang.blaze3d.platform.NativeImage.Format.RGBA, 1, 1, false);
        img.setPixel(0, 0, rgba);
        DynamicTexture tex = new DynamicTexture(() -> "pbr_default", img);
        tex.upload();
        return tex;
    }

    public void loadForTexture(ResourceLocation location, AbstractTexture texture) {
        if (texture == null || location == null) return;
        String key = location.toString();
        if (holders.containsKey(key)) return;

        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        if (rm == null) return;

        PBRTextureLoader<?> loader = resolveLoader(texture);
        if (loader == null) return;

        consumer.clear();
        try {
            loadWithLoader(texture, location, loader, rm);
            PBRTextureHolder holder = consumer.toHolder();
            holders.put(key, holder);
        } catch (Exception e) {
            LOGGER.warn("Failed to load PBR textures for {}", location, e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void loadWithLoader(AbstractTexture texture, ResourceLocation location, PBRTextureLoader loader, ResourceManager rm) {
        loader.load(texture, location, rm, consumer);
    }

    private PBRTextureLoader<?> resolveLoader(AbstractTexture texture) {
        if (texture == null) return null;
        Class<? extends AbstractTexture> clazz = texture.getClass();
        PBRTextureLoader<?> loader = PBRTextureLoaderRegistry.INSTANCE.getLoader(clazz);
        if (loader == null && texture instanceof TextureAtlas) {
            loader = PBRTextureLoaderRegistry.INSTANCE.getLoader(TextureAtlas.class);
        }
        if (loader == null && texture instanceof SimpleTexture) {
            loader = PBRTextureLoaderRegistry.INSTANCE.getLoader(SimpleTexture.class);
        }
        return loader;
    }

    public PBRTextureHolder getHolder(ResourceLocation location) {
        if (location == null) return defaultHolder;
        PBRTextureHolder holder = holders.get(location.toString());
        return holder != null ? holder : defaultHolder;
    }

    public void onTextureDeleted(ResourceLocation location) {
        if (location != null) holders.remove(location.toString());
    }

    public void clear() {
        holders.clear();
    }

    public void close() {
        clear();
        defaultNormalTexture.close();
        defaultSpecularTexture.close();
        defaultHeightTexture.close();
        defaultAoTexture.close();
    }

    private class ConsumerImpl implements PBRTextureLoader.PBRTextureConsumer {
        private AbstractTexture normalTexture;
        private AbstractTexture specularTexture;
        private AbstractTexture heightTexture;
        private AbstractTexture aoTexture;
        private boolean changed;

        void clear() {
            normalTexture = defaultNormalTexture;
            specularTexture = defaultSpecularTexture;
            heightTexture = defaultHeightTexture;
            aoTexture = defaultAoTexture;
            changed = false;
        }

        PBRTextureHolder toHolder() {
            if (!changed) return defaultHolder;
            AbstractTexture n = normalTexture;
            AbstractTexture s = specularTexture;
            AbstractTexture h = heightTexture;
            AbstractTexture a = aoTexture;
            return new PBRTextureHolder() {
                @Override public @NotNull AbstractTexture normalTexture() { return n; }
                @Override public @NotNull AbstractTexture specularTexture() { return s; }
                @Override public @NotNull AbstractTexture heightTexture() { return h; }
                @Override public @NotNull AbstractTexture aoTexture() { return a; }
            };
        }

        @Override public void acceptNormalTexture(@NotNull AbstractTexture t) { normalTexture = t; changed = true; }
        @Override public void acceptSpecularTexture(@NotNull AbstractTexture t) { specularTexture = t; changed = true; }
        @Override public void acceptHeightTexture(@NotNull AbstractTexture t) { heightTexture = t; changed = true; }
        @Override public void acceptAoTexture(@NotNull AbstractTexture t) { aoTexture = t; changed = true; }
    }
}
