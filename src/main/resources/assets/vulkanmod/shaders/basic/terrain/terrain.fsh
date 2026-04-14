#version 450

#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler0;
layout(binding = 4) uniform sampler2D Sampler5;
layout(binding = 5) uniform sampler2D Sampler6;

layout(binding = 1) uniform UBO {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
    float AlphaCutout;
    vec3 cameraPosition;
    vec3 sunPosition;
    vec3 shadowLightPosition;
    mat4 shadowModelView;
    mat4 shadowProjection;
    float dayMoment;
    float dayMixer;
    float nightMixer;
    float rainStrength;
    float dayNightMix;
    int moonPhase;
    int terrainShadowQuality;
    int terrainLightingQuality;
    int blockEmissiveTexturesEnabled;
};

#define MAKEUP_TERRAIN_SHADOW_QUALITY terrainShadowQuality
#include "makeup_lighting.glsl"

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;
layout(location = 4) flat in uint materialFlags;
layout(location = 5) in vec4 rawVertexColor;
layout(location = 6) in vec3 cameraRelativePosition;
layout(location = 7) in vec3 lightColor;
layout(location = 8) in vec2 rawLightLevels;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texColor = texture(Sampler0, texCoord0);
    vec4 color = texColor * vertexColor;
    if (color.a < AlphaCutout) {
        discard;
    }

    vec3 baseColor = texColor.rgb * rawVertexColor.rgb;
    vec3 litColor;
    if (terrainLightingQuality >= 2) {
        litColor = color.rgb;
    } else if (terrainLightingQuality == 1) {
        litColor = makeup_apply_fast_terrain_lighting(color.rgb, baseColor, rawLightLevels);
    } else {
        vec3 worldNormal = makeup_terrain_normal(cameraRelativePosition);
        litColor = makeup_apply_terrain_lighting(
            color.rgb,
            baseColor,
            worldNormal,
            rawLightLevels,
            cameraRelativePosition
        );
    }

    if (blockEmissiveTexturesEnabled != 0) {
        litColor += texture(Sampler6, texCoord0).rgb;
    }

    fragColor = apply_fog(
        vec4(litColor, color.a),
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );
}
