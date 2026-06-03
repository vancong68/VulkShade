package net.vulkanmod.render.pbr.loader;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.NotNull;

public interface PBRTextureLoader<T extends AbstractTexture> {
    void load(T texture, ResourceLocation location, ResourceManager resourceManager, PBRTextureConsumer pbrTextureConsumer);

    interface PBRTextureConsumer {
        void acceptNormalTexture(@NotNull AbstractTexture texture);
        void acceptSpecularTexture(@NotNull AbstractTexture texture);
        void acceptHeightTexture(@NotNull AbstractTexture texture);
        void acceptAoTexture(@NotNull AbstractTexture texture);
    }
}
