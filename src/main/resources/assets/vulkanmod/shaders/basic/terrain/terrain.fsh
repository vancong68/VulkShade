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

        LabPBRSpecular spec = pbr_decode_specular(specSample);
        LabPBRNormal pbrNorm = pbr_decode_normal(normSample);

        vec3 geoNormal = makeup_terrain_normal(worldPos);
        vec3 worldNormal = pbr_apply_normal_map(geoNormal, pbrNorm.normal, worldPos, uv);
        worldNormal = faceforward(worldNormal, worldPos, worldNormal);

        vec3 V = normalize(-worldPos);
        vec3 L = normalize(shadowLightPosition);
        vec3 sunRadiance = makeup_direct_light_color() * dayNightMix * (1.0 - rainStrength * 0.75);

        vec3 F0;
        if (spec.metalFactor > 0.5) {
            F0 = pbr_f0_for_metal(baseColor, spec.metalID);
        } else {
            F0 = pbr_f0_for_dielectric(spec.f0);
        }

        vec2 illum = makeup_adjust_light_levels(rawLightLevels);
        float visibleSky = illum.y;
        float blockLight = illum.x;

        float rawDirectLight = makeup_raw_direct_light(worldNormal);
        float NdotL_sun = clamp(rawDirectLight, 0.0, 1.0) * visibleSky;

        float shadowValue = makeup_terrain_shadow_value(worldPos, worldNormal);

        vec3 directLighting = pbr_cook_torrance(
            worldNormal, V, L, sunRadiance * shadowValue,
            F0, spec.roughness, baseColor, spec.metalFactor
        );

        vec3 omniLight = makeup_omni_light(visibleSky, rawDirectLight, makeup_direct_light_color());
        vec3 candleColor = makeup_candle_color(blockLight);

        vec3 ambient = omniLight + candleColor;
        vec3 diffuse = baseColor * ambient * pbrNorm.ao;

        vec3 sss = pbr_sss_contribution(worldNormal, V, L, sunRadiance * shadowValue,
                                         baseColor, spec.sss, spec.roughness);

        litColor = diffuse + directLighting + sss;

        if (blockEmissiveTexturesEnabled != 0) {
            litColor += texture(Sampler6, uv).rgb;
        }
        litColor += pbr_emissive_contribution(spec.emissive, baseColor);

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
