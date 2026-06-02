package net.vulkanmod.render.texture.pbr;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public final class PBRResourceScanner {
    private static final ResourceLocation PBR_PROPERTIES_LOCATION = ResourceLocation.fromNamespaceAndPath("minecraft", "optifine/pbr.properties");
    private static final String[] DEFAULT_SPECULAR_SUFFIXES = {"_lpps", "_lpbr", "_s", "_spec", "_specular", "_pbr", "_metallic", "_metalness", "_m", "_met", "_MER", "_METALLIC"};
    private static final String[] DEFAULT_NORMAL_SUFFIXES = {"_lppn", "_lpbn", "_n", "_nr", "_nrm", "_normal", "_norm", "_bump", "_normalmap", "_NRM", "_NORMAL"};

    public static final PBRResourceScanner INSTANCE = new PBRResourceScanner();

    private String[] specularSuffixes = DEFAULT_SPECULAR_SUFFIXES;
    private String[] normalSuffixes = DEFAULT_NORMAL_SUFFIXES;
    private boolean hasPackOverrides = false;

    private PBRResourceScanner() {}

    public void refresh(ResourceManager resourceManager) {
        this.specularSuffixes = DEFAULT_SPECULAR_SUFFIXES;
        this.normalSuffixes = DEFAULT_NORMAL_SUFFIXES;
        this.hasPackOverrides = false;

        Resource resource = resourceManager.getResource(PBR_PROPERTIES_LOCATION).orElse(null);
        if (resource == null) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream inputStream = resource.open()) {
            properties.load(inputStream);
            String specSuffixes = properties.getProperty("suffix.specular");
            String normalSuffixes = properties.getProperty("suffix.normal");

            if (specSuffixes != null && !specSuffixes.isBlank()) {
                this.specularSuffixes = parseSuffixList(specSuffixes);
                this.hasPackOverrides = true;
            }

            if (normalSuffixes != null && !normalSuffixes.isBlank()) {
                this.normalSuffixes = parseSuffixList(normalSuffixes);
                this.hasPackOverrides = true;
            }
        } catch (IOException ignored) {
        }
    }

    public String[] getSpecularSuffixes() {
        return this.specularSuffixes;
    }

    public String[] getNormalSuffixes() {
        return this.normalSuffixes;
    }

    public boolean hasPackOverrides() {
        return this.hasPackOverrides;
    }

    private static String[] parseSuffixList(String raw) {
        String[] split = raw.split(",");
        List<String> suffixes = new ArrayList<>();
        for (String part : split) {
            String value = part.trim();
            if (!value.isEmpty()) {
                if (!value.startsWith("_")) {
                    value = "_" + value;
                }
                suffixes.add(value);
            }
        }
        return suffixes.isEmpty() ? DEFAULT_SPECULAR_SUFFIXES : suffixes.toArray(new String[0]);
    }
}
