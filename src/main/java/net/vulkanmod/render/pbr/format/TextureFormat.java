package net.vulkanmod.render.pbr.format;

import net.vulkanmod.render.pbr.PBRType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public interface TextureFormat {
    String name();

    @Nullable
    String version();

    default List<String> getDefines() {
        List<String> defines = new ArrayList<>();
        String defineName = name().toUpperCase(java.util.Locale.ROOT).replaceAll("-", "_");
        defines.add("MC_TEXTURE_FORMAT_" + defineName);
        String version = version();
        if (version != null) {
            String defineVersion = version.replaceAll("[.-]", "_");
            defines.add("MC_TEXTURE_FORMAT_" + defineName + "_" + defineVersion);
        }
        return defines;
    }

    boolean canInterpolateValues(PBRType pbrType);

    @Nullable
    CustomMipmapGenerator getMipmapGenerator(PBRType pbrType);

    @FunctionalInterface
    interface Factory {
        TextureFormat createFormat(String name, @Nullable String version);
    }
}
