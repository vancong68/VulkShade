package net.vulkanmod.render.pbr.format;

import com.mojang.blaze3d.platform.NativeImage;

public interface CustomMipmapGenerator {
    void generate(NativeImage image, int x, int y, int width, int height, int level);

    interface Provider {
        CustomMipmapGenerator getMipmapGenerator();
    }
}
