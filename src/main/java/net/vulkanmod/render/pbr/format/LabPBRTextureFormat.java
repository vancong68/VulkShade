package net.vulkanmod.render.pbr.format;

import net.vulkanmod.render.pbr.PBRType;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record LabPBRTextureFormat(String name, @Nullable String version) implements TextureFormat {
    @Override
    public boolean canInterpolateValues(PBRType pbrType) {
        return pbrType != PBRType.SPECULAR;
    }

    @Override
    public @Nullable CustomMipmapGenerator getMipmapGenerator(PBRType pbrType) {
        return null;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        LabPBRTextureFormat other = (LabPBRTextureFormat) obj;
        return Objects.equals(name, other.name) && Objects.equals(version, other.version);
    }
}
