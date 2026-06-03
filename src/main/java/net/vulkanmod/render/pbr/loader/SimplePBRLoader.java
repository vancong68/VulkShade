package net.vulkanmod.render.pbr.loader;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.vulkanmod.render.pbr.PBRType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;

public class SimplePBRLoader implements PBRTextureLoader<SimpleTexture> {
    private static final Logger LOGGER = LogManager.getLogger("PBR-SimpleLoader");

    @Override
    public void load(SimpleTexture texture, ResourceLocation location, ResourceManager resourceManager, PBRTextureConsumer consumer) {
        AbstractTexture normalTex = loadPBRTexture(location, resourceManager, PBRType.NORMAL);
        AbstractTexture specularTex = loadPBRTexture(location, resourceManager, PBRType.SPECULAR);
        AbstractTexture heightTex = loadPBRTexture(location, resourceManager, PBRType.HEIGHT);
        AbstractTexture aoTex = loadPBRTexture(location, resourceManager, PBRType.AO);

        if (normalTex != null) consumer.acceptNormalTexture(normalTex);
        if (specularTex != null) consumer.acceptSpecularTexture(specularTex);
        if (heightTex != null) consumer.acceptHeightTexture(heightTex);
        if (aoTex != null) consumer.acceptAoTexture(aoTex);
    }

    private AbstractTexture loadPBRTexture(ResourceLocation baseLocation, ResourceManager resourceManager, PBRType type) {
        String path = baseLocation.getPath();
        if (path.startsWith("textures/")) {
            path = path.substring("textures/".length());
        }
        String pbrPath = "textures/" + type.appendSuffix(path);
        ResourceLocation pbrLocation = ResourceLocation.fromNamespaceAndPath(baseLocation.getNamespace(), pbrPath);

        var optionalResource = resourceManager.getResource(pbrLocation);
        if (optionalResource.isEmpty()) return null;

        try (InputStream stream = optionalResource.get().open()) {
            NativeImage image = NativeImage.read(stream);
            DynamicTexture texture = new DynamicTexture(() -> pbrLocation.toString(), image);
            texture.upload();
            return texture;
        } catch (IOException e) {
            LOGGER.debug("Could not load {} PBR texture for {}", type, baseLocation);
            return null;
        }
    }
}
