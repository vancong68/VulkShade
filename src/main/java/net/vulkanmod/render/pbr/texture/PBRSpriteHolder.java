package net.vulkanmod.render.pbr.texture;

import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jetbrains.annotations.Nullable;

public class PBRSpriteHolder {
    private AbstractTexture normalTexture;
    private AbstractTexture specularTexture;
    private AbstractTexture heightTexture;
    private AbstractTexture aoTexture;

    public void setNormalTexture(AbstractTexture texture) { this.normalTexture = texture; }
    public void setSpecularTexture(AbstractTexture texture) { this.specularTexture = texture; }
    public void setHeightTexture(AbstractTexture texture) { this.heightTexture = texture; }
    public void setAoTexture(AbstractTexture texture) { this.aoTexture = texture; }

    @Nullable
    public AbstractTexture getNormalTexture() { return normalTexture; }

    @Nullable
    public AbstractTexture getSpecularTexture() { return specularTexture; }

    @Nullable
    public AbstractTexture getHeightTexture() { return heightTexture; }

    @Nullable
    public AbstractTexture getAoTexture() { return aoTexture; }

    public boolean hasAny() {
        return normalTexture != null || specularTexture != null
                || heightTexture != null || aoTexture != null;
    }
}
