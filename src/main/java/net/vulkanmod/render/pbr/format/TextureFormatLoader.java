package net.vulkanmod.render.pbr.format;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class TextureFormatLoader {
    private static final Logger LOGGER = LogManager.getLogger("PBR-FormatLoader");
    private static final ResourceLocation FORMAT_LOCATION = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/texture_format.txt");

    @Nullable
    private static TextureFormat currentFormat;

    @Nullable
    public static TextureFormat getFormat() {
        return currentFormat;
    }

    public static void reload() {
        currentFormat = null;
        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        if (resourceManager == null) return;

        var optionalResource = resourceManager.getResource(FORMAT_LOCATION);
        if (optionalResource.isEmpty()) {
            currentFormat = new LabPBRTextureFormat("lab-pbr", "1.3");
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(optionalResource.get().open(), StandardCharsets.UTF_8))) {
            String firstLine = reader.readLine();
            if (firstLine != null) {
                firstLine = firstLine.trim();
                String[] parts = firstLine.split(" ");
                String formatName = parts[0].trim();
                String version = parts.length > 1 ? parts[1].trim() : null;

                TextureFormat.Factory factory = TextureFormatRegistry.INSTANCE.getFactory(formatName);
                if (factory != null) {
                    currentFormat = factory.createFormat(formatName, version);
                    LOGGER.info("Loaded PBR texture format: {} v{}", formatName, version != null ? version : "none");
                } else {
                    LOGGER.warn("Unknown PBR texture format: {}, falling back to LabPBR", formatName);
                    currentFormat = new LabPBRTextureFormat("lab-pbr", "1.3");
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read texture format, defaulting to LabPBR");
            currentFormat = new LabPBRTextureFormat("lab-pbr", "1.3");
        }
    }
}
