#version 460

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
};

layout(push_constant) uniform pushConstant {
    vec3 ModelOffset;
};

layout(location = 0) out vec2 texCoord0;

#define COMPRESSED_VERTEX

#ifdef COMPRESSED_VERTEX
    layout(location = 0) in ivec4 Position;
    layout(location = 1) in uvec2 UV0;
    layout(location = 2) in uint PackedColor;
#else
    layout(location = 0) in vec3 Position;
    layout(location = 1) in vec4 Color;
    layout(location = 2) in vec2 UV0;
#endif

const float UV_INV = 1.0 / 32768.0;
const vec3 POSITION_INV = vec3(1.0 / 2048.0);
const float SHADOW_DIST = 0.83;

vec3 getVertexPosition() {
    const vec3 baseOffset = bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8);

    #ifdef COMPRESSED_VERTEX
        return fma(Position.xyz, POSITION_INV, ModelOffset + baseOffset);
    #else
        return Position.xyz + baseOffset;
    #endif
}

void main() {
    vec4 shadowPos = MVP * vec4(getVertexPosition(), 1.0);
    float dist = length(shadowPos.xy);
    float distortion = dist * SHADOW_DIST + (1.0 - SHADOW_DIST);

    shadowPos.xy /= distortion;
    shadowPos.z *= 0.2;
    gl_Position = shadowPos;
    texCoord0 = UV0 * UV_INV;
}
