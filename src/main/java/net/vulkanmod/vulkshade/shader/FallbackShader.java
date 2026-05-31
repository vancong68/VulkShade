package net.vulkanmod.vulkshade.shader;

import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.SPIRVUtils.SPIRV;
import net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind;

import java.util.HashMap;
import java.util.Map;

public class FallbackShader {
    private static final FallbackShader INSTANCE = new FallbackShader();
    private final Map<String, SPIRV> fallbackCache = new HashMap<>();

    private static final String FALLBACK_VERT = """
        #version 450
        layout(location = 0) in vec3 Position;
        layout(location = 0) out vec4 fragColor;
        layout(binding = 0) uniform UBO {
            mat4 MVP;
        };
        void main() {
            gl_Position = MVP * vec4(Position, 1.0);
            fragColor = vec4(1.0, 0.0, 1.0, 1.0);
        }
        """;

    private static final String FALLBACK_FRAG = """
        #version 450
        layout(location = 0) in vec4 fragColor;
        layout(location = 0) out vec4 outColor;
        void main() {
            outColor = fragColor;
        }
        """;

    private static final String FALLBACK_COMPUTE = """
        #version 450
        layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
        layout(binding = 0, rgba8) uniform readonly image2D inputImage;
        layout(binding = 1, rgba8) uniform writeonly image2D outputImage;
        void main() {
            ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(inputImage);
            if (coord.x < size.x && coord.y < size.y) {
                vec4 color = imageLoad(inputImage, coord);
                imageStore(outputImage, coord, color);
            }
        }
        """;

    private FallbackShader() {
    }

    public static FallbackShader getInstance() {
        return INSTANCE;
    }

    public SPIRV getOrCreateFallback(ShaderKind kind) {
        return switch (kind) {
            case VERTEX_SHADER -> getOrCompile("__fallback_vert", FALLBACK_VERT, ShaderKind.VERTEX_SHADER);
            case FRAGMENT_SHADER -> getOrCompile("__fallback_frag", FALLBACK_FRAG, ShaderKind.FRAGMENT_SHADER);
            case COMPUTE_SHADER -> getOrCompile("__fallback_compute", FALLBACK_COMPUTE, ShaderKind.COMPUTE_SHADER);
            default -> throw new IllegalArgumentException("Unsupported shader kind: " + kind);
        };
    }

    private synchronized SPIRV getOrCompile(String name, String source, ShaderKind kind) {
        return fallbackCache.computeIfAbsent(name, k -> {
            try {
                return SPIRVUtils.compileShader(k, source, kind);
            } catch (Exception e) {
                throw new RuntimeException("Fallback shader compilation failed - this should never happen", e);
            }
        });
    }
}
