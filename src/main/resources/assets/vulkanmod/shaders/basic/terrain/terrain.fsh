#version 450

#include "fog.glsl"
#include "pbr.glsl"

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
    int pbrEnabled;
    int pbrDebugMode;
    float pbrNormalStrength;
    float pbrSpecularStrength;
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

    if (pbrEnabled != 0) {
        vec3 N = normalize(makeup_terrain_normal(cameraRelativePosition));
        vec3 V = normalize(-cameraRelativePosition);
        vec3 L = normalize(shadowLightPosition);

        float NdotL = max(dot(N, L), 0.0);
        float shadowValue = clamp(makeup_terrain_shadow_value(cameraRelativePosition, N), 0.0, 1.0);
        vec3 sunRadiance = makeup_direct_light_color() * dayNightMix * (1.0 - rainStrength * 0.75);

        PBRMaterial mat;
        mat.albedo = baseColor;
        mat.roughness = clamp(0.6, 0.02, 0.98);
        mat.metallic = 0.0;
        mat.ao = clamp(makeup_ambient_occlusion(cameraRelativePosition), 0.0, 1.0);
        mat.emissive = vec3(0.0);
        mat.perceptualRoughness = mat.roughness;

        vec3 F0 = mix(vec3(0.04), mat.albedo, mat.metallic);

        vec3 directSpecular = pbr_cook_torrance(N, V, L, sunRadiance * shadowValue,
                                                  F0, mat.roughness, mat.albedo, mat.metallic);
        directSpecular *= clamp(pbrSpecularStrength, 0.0, 10.0);

        vec3 ambientDiffuse = mat.albedo * lightColor * mat.ao * 0.5;

        litColor = ambientDiffuse + directSpecular;

        if (blockEmissiveTexturesEnabled != 0) {
            vec3 emissiveTex = texture(Sampler6, texCoord0).rgb;
            litColor += emissiveTex;
        }

        litColor = pbr_reinhard_tone_map(litColor);
    } else if (terrainLightingQuality >= 2) {
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
