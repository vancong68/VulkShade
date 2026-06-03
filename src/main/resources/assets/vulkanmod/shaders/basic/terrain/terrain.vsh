#version 460

#include "light.glsl"
#include "fog.glsl"

layout (binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    vec3 cameraPosition;
    float rainStrength;
    float SkyCloudTime;
};

layout (push_constant) uniform pushConstant {
    vec3 ModelOffset;
};

layout (binding = 3) uniform sampler2D Sampler2;


layout (location = 0) out vec4 vertexColor;
layout (location = 1) out vec2 texCoord0;
layout (location = 2) out float sphericalVertexDistance;
layout (location = 3) out float cylindricalVertexDistance;
layout (location = 4) flat out uint materialFlags;
layout (location = 5) out vec4 rawVertexColor;
layout (location = 6) out vec3 cameraRelativePosition;
layout (location = 7) out vec3 lightColor;
layout (location = 8) out vec2 rawLightLevels;

#define COMPRESSED_VERTEX

#ifdef COMPRESSED_VERTEX
    layout (location = 0) in ivec4 Position;
    layout (location = 1) in uvec2 UV0;
    layout (location = 2) in uint PackedColor;
    layout (location = 3) in int WavingData;
#else
    layout (location = 0) in vec3 Position;
    layout (location = 1) in vec4 Color;
    layout (location = 2) in vec2 UV0;
    layout (location = 3) in ivec2 UV2;
    layout (location = 4) in vec3 Normal;
#endif

const float UV_INV = 1.0 / 32768.0;
const vec3 POSITION_INV = vec3(1.0 / 2048.0);
const vec3 POSITION_OFFSET = vec3(4.0);
const uint MATERIAL_WAVING = 0x4u;
const uint MATERIAL_WAVING_SPECIAL = 0x8u;
const uint MATERIAL_WAVING_MASK = MATERIAL_WAVING | MATERIAL_WAVING_SPECIAL;
const float PI = 3.141592653589793;

vec3 getVertexPosition() {
    const vec3 baseOffset = bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8);

    #ifdef COMPRESSED_VERTEX
        return fma(Position.xyz, POSITION_INV, ModelOffset + baseOffset);
    #else
        return Position.xyz + baseOffset;
    #endif
}

vec3 wave_move(vec3 pos) {
    float timer = SkyCloudTime * PI;
    pos = mod(pos, 157.07963267948966);

    vec2 waveX = vec2(timer * 0.5, timer) + pos.xy;
    vec2 waveZ = vec2(timer, timer * 1.5) + pos.xy;
    vec2 waveY = vec2(timer * 0.5, timer * 0.25) - pos.zx;

    waveX = sin(waveX + waveY);
    waveZ = cos(waveZ + waveY);
    return vec3(waveX.x + waveX.y, 0.0, waveZ.x + waveZ.y);
}

vec3 apply_foliage_waving(vec3 pos, uint packedLight, uint wavingFlags) {
    wavingFlags &= MATERIAL_WAVING_MASK;
    if (wavingFlags == 0u) {
        return pos;
    }

    float weight = 1.0;
    if (wavingFlags == MATERIAL_WAVING_SPECIAL) {
        weight = 0.3;
    } else if (wavingFlags == MATERIAL_WAVING_MASK) {
        weight = 2.0;
    }

    float skyLight = float(bitfieldExtract(packedLight, 8, 4)) / 15.0;
    vec3 worldPos = pos + cameraPosition;
    vec3 waveOffset = wave_move(worldPos.xzy) * weight * (skyLight * skyLight) * (0.03 + (rainStrength * 0.05));
    return pos + waveOffset.xzy;
}

void main() {
    const uint packedLight = uint(Position.a);
    const vec3 pos = apply_foliage_waving(getVertexPosition(), packedLight, uint(WavingData));
    gl_Position = MVP * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

    const vec4 Color = unpackUnorm4x8(PackedColor);
    const vec4 lightSample = sample_lightmap2(Sampler2, packedLight);
    vertexColor = Color * lightSample;
    materialFlags = packedLight & 0xFu;
    rawVertexColor = Color;
    cameraRelativePosition = pos;
    lightColor = lightSample.rgb;

    // Extract raw block/sky light levels (0-1) from packed light for MakeUp lighting
    float blockLight = float(bitfieldExtract(packedLight, 4, 4)) / 15.0;
    float skyLight = float(bitfieldExtract(packedLight, 8, 4)) / 15.0;
    rawLightLevels = vec2(blockLight, skyLight);

    texCoord0 = UV0 * UV_INV;
}
