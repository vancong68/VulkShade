package net.vulkanmod.render.pbr.shader;

import net.vulkanmod.Initializer;

public class PBRShaderUniforms {
    public static boolean isEnabled() {
        return Initializer.CONFIG.featurePBR;
    }

    public static int getPBRMode() {
        return Initializer.CONFIG.pbrDebugMode;
    }

    public static float getNormalStrength() {
        return Initializer.CONFIG.pbrNormalStrength;
    }

    public static float getSpecularStrength() {
        return Initializer.CONFIG.pbrSpecularStrength;
    }
}
