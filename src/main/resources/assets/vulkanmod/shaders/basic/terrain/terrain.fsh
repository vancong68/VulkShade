#version 450

#include "fog.glsl"
#include "pbr.glsl"

layout(binding = 2) uniform sampler2D Sampler0;
layout(binding = 4) uniform sampler2D Sampler5;
layout(binding = 5) uniform sampler2D Sampler6;

layout(binding = 6) uniform sampler2D Sampler8;
layout(binding = 7) uniform sampler2D Sampler9;
layout(binding = 8) uniform sampler2D Sampler10;
layout(binding = 9) uniform sampler2D Sampler11;

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
    int pomEnabled;
    float pomHeightScale;
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

    vec3 baseColorSRGB = texColor.rgb * rawVertexColor.rgb;
    vec3 litColor;

    if (pbrEnabled != 0) {
        float fragDistance = length(cameraRelativePosition);
        vec3 V = normalize(-cameraRelativePosition);
        vec3 worldNormal = makeup_terrain_normal(cameraRelativePosition);

        mat3 TBN = pbr_construct_tbn(cameraRelativePosition, texCoord0, worldNormal);

        vec2 pbrUV = texCoord0;
        if (pomEnabled != 0) {
            vec3 V_ts = transpose(TBN) * V;
            pbrUV = pbr_apply_pom(Sampler11, texCoord0, V_ts, pomHeightScale, fragDistance);
        }

        vec3 baseColorLinear = pbr_srgb_to_linear(baseColorSRGB);

        PBRMaterial mat = pbr_material_from_textures(
            baseColorLinear, Sampler8, Sampler9, Sampler10,
            pbrUV, TBN, pbrNormalStrength
        );

        vec3 N = mat.worldNormal;
        vec3 L = normalize(shadowLightPosition);
        float NdotL = max(dot(N, L), 0.0);
        float shadowValue = clamp(makeup_terrain_shadow_value(cameraRelativePosition, N), 0.0, 1.0);
        float sunVisibility = (1.0 - rainStrength * 0.75);
        vec3 sunRadiance = makeup_direct_light_color() * dayNightMix * sunVisibility;

        PBRLight light;
        light.direction = L;
        light.radiance = sunRadiance;
        light.attenuation = shadowValue;

        vec3 directResult = pbr_evaluate(mat, light, N, V);
        vec3 ambientResult = pbr_evaluate_ibl_approx(mat, N, V, rawLightLevels, dayNightMix);

        vec3 pbrLit = directResult + ambientResult;

        if (blockEmissiveTexturesEnabled != 0) {
            vec3 emissiveTex = texture(Sampler6, pbrUV).rgb;
            pbrLit += emissiveTex;
        }

        pbrLit = pbr_debug_view(pbrDebugMode, mat, N, pbrLit, NdotL);

        // Output linear HDR for post-process tonemap pass
        // (no Reinhard here — ACES in tonemap.fsh handles it)
        litColor = pbrLit;
    } else if (terrainLightingQuality >= 2) {
        litColor = color.rgb;
    } else if (terrainLightingQuality == 1) {
        litColor = makeup_apply_fast_terrain_lighting(color.rgb, baseColorSRGB, rawLightLevels);
    } else {
        vec3 worldNormal = makeup_terrain_normal(cameraRelativePosition);
        litColor = makeup_apply_terrain_lighting(
            color.rgb,
            baseColorSRGB,
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
