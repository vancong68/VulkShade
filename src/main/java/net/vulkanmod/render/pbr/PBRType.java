package net.vulkanmod.render.pbr;

import org.apache.commons.io.FilenameUtils;

public enum PBRType {
    NORMAL("_n", 0x7F7FFFFF),
    SPECULAR("_s", 0x00000000),
    HEIGHT("_h", 0x00000000),
    AO("_ao", 0xFFFFFFFF);

    private static final PBRType[] VALUES = values();
    private final String suffix;
    private final int defaultValue;

    PBRType(String suffix, int defaultValue) {
        this.suffix = suffix;
        this.defaultValue = defaultValue;
    }

    public static String removeSuffix(String path) {
        int extensionIndex = FilenameUtils.indexOfExtension(path);
        String pathNoExtension = path.substring(0, extensionIndex);
        for (PBRType type : VALUES) {
            if (pathNoExtension.endsWith(type.suffix)) {
                String base = pathNoExtension.substring(0, pathNoExtension.length() - type.suffix.length());
                return base + path.substring(extensionIndex);
            }
        }
        return null;
    }

    public static PBRType fromFileLocation(String location) {
        String pathNoExtension = location.contains(".") ? location.substring(0, location.lastIndexOf('.')) : location;
        for (PBRType type : VALUES) {
            if (pathNoExtension.endsWith(type.suffix)) {
                return type;
            }
        }
        return null;
    }

    public String getSuffix() {
        return suffix;
    }

    public int getDefaultValue() {
        return defaultValue;
    }

    public String appendSuffix(String path) {
        int extensionIndex = FilenameUtils.indexOfExtension(path);
        if (extensionIndex != -1) {
            return path.substring(0, extensionIndex) + suffix + path.substring(extensionIndex);
        } else {
            return path + suffix;
        }
    }
}
