package net.vulkanmod.render.shader;

import com.google.common.collect.Sets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public abstract class ShaderLoadUtil {

    public static final String RESOURCES_PATH = SPIRVUtils.class.getResource("/assets/vulkanmod").toExternalForm();
    public static final String SHADERS_PATH = "%s/shaders/".formatted(RESOURCES_PATH);

    public static final Set<String> REMAPPED_SHADERS = Sets.newHashSet(
            "core/screenquad.vsh",
            "core/entity.vsh",
            "core/entity.fsh",
            "core/shadow_entity.vsh",
            "core/shadow_entity.fsh",
            "core/rendertype_item_entity_translucent_cull.vsh",
            "core/rendertype_item_entity_translucent_cull.fsh"
    );

    public static String resolveShaderPath(String path) {
        return resolveShaderPath(SHADERS_PATH, path);
    }

    public static String resolveShaderPath(String shaderPath, String path) {
        return "%s%s".formatted(shaderPath, path);
    }

    public static void loadShaders(Pipeline.Builder pipelineBuilder, JsonObject config, String configName, String path) {
        String vertexShader = config.has("vertex") ? config.get("vertex").getAsString() : configName;
        String fragmentShader = config.has("fragment") ? config.get("fragment").getAsString() : configName;

        if (vertexShader == null) {
            vertexShader = configName;
        }
        if (fragmentShader == null) {
            fragmentShader = configName;
        }

        vertexShader = removeNameSpace(vertexShader);
        fragmentShader = removeNameSpace(fragmentShader);

        vertexShader = getFileName(vertexShader);
        fragmentShader = getFileName(fragmentShader);

        loadShader(pipelineBuilder, configName, path, vertexShader, SPIRVUtils.ShaderKind.VERTEX_SHADER);
        loadShader(pipelineBuilder, configName, path, fragmentShader, SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
    }

    public static void loadShader(Pipeline.Builder pipelineBuilder, String configName, String path, SPIRVUtils.ShaderKind type) {
        String[] splitPath = splitPath(path);
        String shaderName = splitPath[1];
        String subPath = splitPath[0];

        loadShader(pipelineBuilder, configName, subPath, shaderName, type);
    }

    public static void loadShader(Pipeline.Builder pipelineBuilder, String configName, String path, String shaderName, SPIRVUtils.ShaderKind type) {
        String source = getShaderSource(path, configName, shaderName, type);

        SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(shaderName, source, type);

        switch (type) {
            case VERTEX_SHADER -> pipelineBuilder.setVertShaderSPIRV(spirv);
            case FRAGMENT_SHADER -> pipelineBuilder.setFragShaderSPIRV(spirv);
        }
    }

    public static String getConfigFilePath(String path, String rendertype) {
        String basePath = "%s/shaders/%s".formatted(RESOURCES_PATH, path);
        String configPath = "%s/%s/%s.json".formatted(basePath, rendertype, rendertype);

        if (tryPathExists(configPath)) return configPath;

        String fallbackPath = "%s/%s.json".formatted(basePath, rendertype);
        if (tryPathExists(fallbackPath)) return fallbackPath;

        return null;
    }

    private static boolean tryPathExists(String uriString) {
        try {
            URI uri = new URI(uriString);
            if (uri.getScheme() == null) return false;
            if (uri.getScheme().equals("jar") || uri.getScheme().equals("zip")) {
                String entry = uri.getSchemeSpecificPart();
                if (entry.contains("!")) {
                    String innerPath = entry.substring(entry.indexOf('!') + 1);
                    return ShaderLoadUtil.class.getResource(innerPath) != null;
                }
                return false;
            }
            if (uri.getScheme().equals("file")) {
                Path p = Paths.get(uri);
                return Files.exists(p);
            }
            return false;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    public static JsonObject getJsonConfig(String path, String rendertype) {
        // Check for external shader
        if (rendertype.contains(String.valueOf(ResourceLocation.NAMESPACE_SEPARATOR))) {
            return null;
        }

        String basePath = path;
        String configPath = "%s/%s/%s.json".formatted(basePath, rendertype, rendertype);

        InputStream stream;
        try {
            stream = getInputStream(configPath);

            if (stream == null) {
                configPath = "%s/%s.json".formatted(basePath, rendertype);
                stream = getInputStream(configPath);
            }

            if (stream == null) {
                return null;
            }

            JsonElement jsonElement = JsonParser.parseReader(new BufferedReader(new InputStreamReader(stream)));
            stream.close();

            return (JsonObject) jsonElement;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }

    }

    public static String getShaderSource(ResourceLocation resourceLocation, ShaderType type) {
        String shaderExtension = switch (type) {
            case VERTEX -> ".vsh";
            case FRAGMENT -> ".fsh";
        };

        String path = resourceLocation.getPath();
        String[] splitPath = splitPath(path);
        String shaderName = "%s%s".formatted(splitPath[1], shaderExtension);
        String shaderFile = "%s/shaders/%s/%s".formatted(RESOURCES_PATH, path, shaderName);

        InputStream stream;
        try {
            stream = getInputStream(shaderFile);

            if (stream == null) {
                return null;
            }

            String source = IOUtils.toString(new BufferedReader(new InputStreamReader(stream)));
            stream.close();

            return source;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static String getShaderSource(String path, ShaderType type) {
        String shaderExtension = switch (type) {
            case VERTEX -> ".vsh";
            case FRAGMENT -> ".fsh";
        };

        String[] splitPath = splitPath(path);
        String shaderName = "%s%s".formatted(splitPath[1], shaderExtension);

        String shaderFile = "%s/shaders/%s/%s".formatted(RESOURCES_PATH, path, shaderName);

        InputStream stream;
        try {
            stream = getInputStream(shaderFile);
            String source = IOUtils.toString(new BufferedReader(new InputStreamReader(stream)));
            stream.close();

            return source;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static String getShaderSource(String path, String configName, String shaderName, SPIRVUtils.ShaderKind type) {
        String shaderExtension = switch (type) {
            case VERTEX_SHADER -> ".vsh";
            case FRAGMENT_SHADER -> ".fsh";
            case COMPUTE_SHADER -> ".comp";
            default -> throw new UnsupportedOperationException("shader type %s unsupported");
        };

        String basePath = path;

        String shaderPath = "/%s/%s".formatted(configName, configName);
        String shaderFile = "%s%s%s".formatted(basePath, shaderPath, shaderExtension);

        InputStream stream;
        try {
            stream = getInputStream(shaderFile);

            if (stream == null) {
                shaderPath = "/%s".formatted(shaderName);
                shaderFile = "%s%s%s".formatted(basePath, shaderPath, shaderExtension);
                stream = getInputStream(shaderFile);
            }

            if (stream == null) {
                shaderPath = "/%s/%s".formatted(configName, shaderName);
                shaderFile = "%s%s%s".formatted(basePath, shaderPath, shaderExtension);
                stream = getInputStream(shaderFile);
            }

            if (stream == null) {
                shaderPath = "/%s/%s".formatted(shaderName, shaderName);
                shaderFile = "%s%s%s".formatted(basePath, shaderPath, shaderExtension);
                stream = getInputStream(shaderFile);
            }

            if (stream == null) {
                return null;
            }

            String source = IOUtils.toString(new BufferedReader(new InputStreamReader(stream)));
            stream.close();

            return source;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static String getFileName(String path) {
        int idx = path.lastIndexOf('/');
        return idx > -1 ? path.substring(idx + 1) : path;
    }

    public static String removeNameSpace(String path) {
        int idx = path.indexOf(':');
        return idx > -1 ? path.substring(idx + 1) : path;
    }

    public static String[] splitPath(String path) {
        int idx = path.lastIndexOf('/');

        return new String[] {path.substring(0, idx), path.substring(idx + 1)};
    }

    public static InputStream getInputStream(String uriString) {
        try {
            URI uri = new URI(uriString);
            if (uri.getScheme() == null) return null;

            if (uri.getScheme().equals("jar") || uri.getScheme().equals("zip")) {
                String full = uriString;
                if (full.contains("!")) {
                    String innerPath = full.substring(full.indexOf('!') + 1);
                    InputStream in = ShaderLoadUtil.class.getResourceAsStream(innerPath);
                    if (in == null) {
                        String alt = innerPath.startsWith("/") ? innerPath : "/" + innerPath;
                        in = ShaderLoadUtil.class.getResourceAsStream(alt);
                    }
                    return in;
                }
                return null;
            }

            if (uri.getScheme().equals("file")) {
                Path filePath = Paths.get(uri);
                if (!Files.exists(filePath)) return null;
                return Files.newInputStream(filePath);
            }

            return null;
        } catch (URISyntaxException | IOException e) {
            return null;
        }
    }
}
