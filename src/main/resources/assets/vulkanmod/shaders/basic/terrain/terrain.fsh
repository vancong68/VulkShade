#version 450

#include "fog.glsl"
#include "pbr.glsl"

layout(binding = 2) uniform sampler2D Sampler0;
layout(binding = 4) uniform sampler2D Sampler5;
layout(binding = 5) uniform sampler2D Sampler6;
layout(binding = 6) uniform sampler2D Sampler7;
layout(binding = 7) uniform sampler2D Sampler8;

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
    float pbrFallbackRoughness;
    float pbrFallbackMetallic;
    float pbrFallbackF0;
    float pbrFallbackAO;
    float pbrFallbackEmissive;
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
    vec2 uv = texCoord0;
    vec3 worldPos = cameraRelativePosition;

    if (pbrEnabled != 0) {
        vec3 geoNormal = makeup_terrain_normal(worldPos);
        vec3 V = normalize(-worldPos);
        mat3 TBN = pbr_tangent_to_world(geoNormal, worldPos, uv);
        vec3 viewDirTS = transpose(TBN) * V;

        if (viewDirTS.z > 0.0) {
            uv = pbr_parallax_mapping(Sampler8, uv, viewDirTS, 0.04);
        }
    }

    vec4 texColor = texture(Sampler0, uv);
    vec4 color = texColor * vertexColor;
    if (color.a < AlphaCutout) {
        discard;
    }

    vec3 baseColor = texColor.rgb * rawVertexColor.rgb;
    vec3 litColor;

    if (pbrEnabled != 0) {
        vec4 specSample = texture(Sampler7, uv);
        vec4 normSample = texture(Sampler8, uv);

        vec3 geoNormal = makeup_terrain_normal(worldPos);
        vec3 V = normalize(-worldPos);
        vec3 L = normalize(shadowLightPosition);
        vec3 sunRadiance = makeup_direct_light_color() * dayNightMix * (1.0 - rainStrength * 0.75);

        vec3 F0;
        float roughness;
        float metallic;
        float ao;
        vec3 emissiveColor;
        float sss;

        bool hasPBR = specSample.a > 0.001;

        if (hasPBR) {
            LabPBRSpecular spec = pbr_decode_specular(specSample);
            LabPBRNormal pbrNorm = pbr_decode_normal(normSample);

            vec3 worldNormal = pbr_apply_normal_map(geoNormal, pbrNorm.normal, worldPos, uv);
            worldNormal = faceforward(worldNormal, worldPos, worldNormal);

            if (spec.metalFactor > 0.5) {
                F0 = pbr_f0_for_metal(baseColor, spec.metalID);
            } else {
                F0 = pbr_f0_for_dielectric(spec.f0);
            }

            roughness = spec.roughness;
            metallic = spec.metalFactor;
            ao = pbrNorm.ao;
            sss = spec.sss;
            emissiveColor = pbr_emissive_contribution(spec.emissive, baseColor);

            vec2 illum = makeup_adjust_light_levels(rawLightLevels);
            float visibleSky = illum.y;
            float blockLight = illum.x;

            float rawDirectLight = makeup_raw_direct_light(worldNormal);
            float shadowValue = makeup_terrain_shadow_value(worldPos, worldNormal);

            vec3 directLighting = pbr_cook_torrance(
                worldNormal, V, L, sunRadiance * shadowValue,
                F0, roughness, baseColor, metallic
            );

            vec3 omniLight = makeup_omni_light(visibleSky, rawDirectLight, makeup_direct_light_color());
            vec3 candleColor = makeup_candle_color(blockLight);

            vec3 ambient = omniLight + candleColor;
            vec3 diffuse = baseColor * ambient * ao;

            vec3 sssLight = pbr_sss_contribution(worldNormal, V, L, sunRadiance * shadowValue,
                                                  baseColor, sss, roughness);

            litColor = diffuse + directLighting + sssLight;

            if (blockEmissiveTexturesEnabled != 0) {
                litColor += texture(Sampler6, uv).rgb;
            }
            litColor += emissiveColor;

        } else {
            vec3 worldNormal = geoNormal;
            worldNormal = faceforward(worldNormal, worldPos, worldNormal);

            F0 = pbr_f0_for_dielectric(pbrFallbackF0);
            roughness = clamp(pbrFallbackRoughness, 0.001, 0.999);
            metallic = pbrFallbackMetallic;
            ao = pbrFallbackAO;
            sss = 0.0;
            emissiveColor = pbr_emissive_contribution(pbrFallbackEmissive, baseColor);

            vec2 illum = makeup_adjust_light_levels(rawLightLevels);
            float visibleSky = illum.y;
            float blockLight = illum.x;

            float rawDirectLight = makeup_raw_direct_light(worldNormal);
            float shadowValue = makeup_terrain_shadow_value(worldPos, worldNormal);

            vec3 directLighting = pbr_cook_torrance(
                worldNormal, V, L, sunRadiance * shadowValue,
                F0, roughness, baseColor, metallic
            );

            vec3 omniLight = makeup_omni_light(visibleSky, rawDirectLight, makeup_direct_light_color());
            vec3 candleColor = makeup_candle_color(blockLight);

            vec3 ambient = omniLight + candleColor;
            vec3 diffuse = baseColor * ambient * ao;

            litColor = diffuse + directLighting;

            if (blockEmissiveTexturesEnabled != 0) {
                litColor += texture(Sampler6, uv).rgb;
            }
            litColor += emissiveColor;
        }

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
