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
layout(location = 9) flat in uint packedLightRaw;

const uint MATERIAL_ROCK = 1u;
const uint MATERIAL_WOOD = 2u;
const uint MATERIAL_METAL = 3u;
const uint MATERIAL_GLASS = 4u;
const uint MATERIAL_LEAF = 5u;
const uint MATERIAL_ORGANIC = 6u;
const uint MATERIAL_SAND = 7u;
const uint MATERIAL_DIRT = 8u;
const uint MATERIAL_WATER = 9u;
const uint MATERIAL_ICE = 10u;
const uint MATERIAL_EMISSIVE = 11u;

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
        PBRMaterialParams mat = pbr_get_material_params(materialFlags);

        vec3 geoNormal = makeup_terrain_normal(cameraRelativePosition);
        if (any(isnan(geoNormal)) || any(isinf(geoNormal))) {
            geoNormal = vec3(0.0, 1.0, 0.0);
        }
        geoNormal = normalize(geoNormal);
        if (pbrNormalStrength > 0.001) {
            geoNormal = pbr_generate_normal(Sampler0, texCoord0, cameraRelativePosition, pbrNormalStrength);
        }

        vec3 V = normalize(-cameraRelativePosition);
        if (any(isnan(V)) || any(isinf(V))) V = vec3(0.0, 0.0, 1.0);

        vec3 L = normalize(shadowLightPosition);
        if (any(isnan(L)) || any(isinf(L))) L = vec3(0.0, 1.0, 0.0);

        vec3 sunRadiance = makeup_direct_light_color() * dayNightMix * (1.0 - rainStrength * 0.75);

        vec3 F0 = vec3(0.04);
        float roughness = clamp(mat.roughness, 0.02, 0.98);
        float metallic = mat.metallic;
        float ao = clamp(mat.ao, 0.0, 1.0);

        if (metallic > 0.5) {
            F0 = max(baseColor, vec3(0.04));
        }

        float wetness = clamp(rainStrength * 0.65 + (1.0 - ao) * 0.15, 0.0, 1.0);
        roughness = pbr_wetness_roughness(roughness, wetness);
        baseColor = pbr_wetness_color(baseColor, wetness);

        vec2 illum = makeup_adjust_light_levels(rawLightLevels);
        float visibleSky = clamp(illum.y, 0.0, 1.0);
        float blockLight = clamp(illum.x, 0.0, 1.0);

        float shadowValue = clamp(makeup_terrain_shadow_value(cameraRelativePosition, geoNormal), 0.0, 1.0);

        vec3 ambientDiffuse = baseColor * lightColor * ao;

        float NdotL = max(dot(geoNormal, L), 0.0);

        vec3 directSpecular = pbr_cook_torrance(
            geoNormal, V, L, sunRadiance * shadowValue,
            F0, roughness, baseColor, metallic
        );
        if (any(isnan(directSpecular)) || any(isinf(directSpecular))) directSpecular = vec3(0.0);
        directSpecular *= clamp(pbrSpecularStrength, 0.0, 10.0);

        litColor = ambientDiffuse + directSpecular;

        vec3 sssLight = vec3(0.0);
        if (mat.sss > 0.01) {
            sssLight = pbr_sss_contribution(geoNormal, V, L, sunRadiance * shadowValue,
                                             baseColor, mat.sss, roughness);
            if (any(isnan(sssLight)) || any(isinf(sssLight))) sssLight = vec3(0.0);
            litColor += sssLight;
        }

        if (blockEmissiveTexturesEnabled != 0) {
            vec3 emissiveTex = texture(Sampler6, texCoord0).rgb;
            if (!any(isnan(emissiveTex)) && !any(isinf(emissiveTex))) {
                litColor += emissiveTex;
            }
        }

        if (materialFlags == MATERIAL_EMISSIVE) {
            litColor += baseColor * 2.0;
        }

        if (pbrDebugMode != 0) {
            vec3 emissiveColor = materialFlags == MATERIAL_EMISSIVE ? baseColor : vec3(0.0);
            litColor = pbr_debug_visualize(pbrDebugMode, baseColor, roughness, metallic, ao,
                                           emissiveColor, materialFlags, geoNormal,
                                           visibleSky, blockLight, litColor,
                                           shadowValue, directSpecular, NdotL, packedLightRaw);
        }

        if (any(isnan(litColor)) || any(isinf(litColor))) litColor = baseColor;
        litColor = clamp(litColor, 0.0, 1e6);
        litColor = litColor / (litColor + 1.0);

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
