#ifndef PBR_GLSL
#define PBR_GLSL

const float PBR_PI = 3.14159265359;

// ===================== Material Parameter Lookup =====================

struct PBRMaterialParams {
    float roughness;
    float metallic;
    float ao;
    float emissive;
    float sss;
};

PBRMaterialParams pbr_get_material_params(uint materialClass) {
    PBRMaterialParams p;
    p.roughness = 0.85;
    p.metallic = 0.0;
    p.ao = 0.70;
    p.emissive = 0.0;
    p.sss = 0.0;

    switch (materialClass) {
        case 1u: // ROCK
            p.roughness = 0.85; p.metallic = 0.0; p.ao = 0.90; break;
        case 2u: // WOOD
            p.roughness = 0.75; p.metallic = 0.0; p.ao = 0.88; break;
        case 3u: // METAL
            p.roughness = 0.08; p.metallic = 1.0; p.ao = 0.85; break;
        case 4u: // GLASS
            p.roughness = 0.03; p.metallic = 0.0; p.ao = 0.15; break;
        case 5u: // LEAF
            p.roughness = 0.65; p.metallic = 0.0; p.ao = 0.80; p.sss = 0.3; break;
        case 6u: // ORGANIC
            p.roughness = 0.80; p.metallic = 0.0; p.ao = 0.85; p.sss = 0.2; break;
        case 7u: // SAND
            p.roughness = 0.95; p.metallic = 0.0; p.ao = 0.90; break;
        case 8u: // DIRT
            p.roughness = 0.92; p.metallic = 0.0; p.ao = 0.90; break;
        case 9u: // WATER
            p.roughness = 0.01; p.metallic = 0.0; p.ao = 0.05; break;
        case 10u: // ICE
            p.roughness = 0.05; p.metallic = 0.0; p.ao = 0.10; break;
        case 11u: // EMISSIVE
            p.roughness = 0.65; p.metallic = 0.0; p.ao = 1.0; p.emissive = 1.0; break;
    }

    return p;
}

// ===================== PBR BRDF Functions =====================

float pbr_distribution_ggx(vec3 N, vec3 H, float roughness) {
    float a = max(roughness * roughness, 0.0001);
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;
    float denom = NdotH2 * (a2 - 1.0) + 1.0;
    return a2 / max(PBR_PI * denom * denom, 0.000001);
}

float pbr_geometry_schlick_ggx(float NdotV, float roughness) {
    float r = max(roughness + 1.0, 0.001);
    float k = (r * r) / 8.0;
    return NdotV / max(NdotV * (1.0 - k) + k, 0.000001);
}

float pbr_geometry_smith(vec3 N, vec3 V, vec3 L, float roughness) {
    return pbr_geometry_schlick_ggx(max(dot(N, V), 0.0), roughness)
         * pbr_geometry_schlick_ggx(max(dot(N, L), 0.0), roughness);
}

vec3 pbr_fresnel_schlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 pbr_cook_torrance(vec3 N, vec3 V, vec3 L, vec3 radiance,
                       vec3 F0, float roughness, vec3 albedo, float metallic) {
    float NdotV = max(dot(N, V), 0.001);
    float NdotL = max(dot(N, L), 0.0);

    vec3 H = normalize(V + L);
    if (any(isnan(H)) || any(isinf(H))) H = vec3(0.0, 0.0, 1.0);

    float NDF = pbr_distribution_ggx(N, H, roughness);
    if (isnan(NDF) || isinf(NDF)) NDF = 0.0;

    float G = pbr_geometry_smith(N, V, L, roughness);
    if (isnan(G) || isinf(G)) G = 0.0;

    vec3 F = pbr_fresnel_schlick(max(dot(H, V), 0.0), F0);

    vec3 kS = F;
    vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);
    float denom = max(4.0 * NdotV * NdotL, 0.001);

    vec3 specular = NDF * G * F / denom;
    return (kD * albedo / PBR_PI + specular) * radiance * NdotL;
}

// ===================== Wetness System =====================

float pbr_wetness_roughness(float roughness, float wetness) {
    return clamp(mix(roughness, max(roughness * 0.55, 0.05), wetness), 0.001, 0.999);
}

vec3 pbr_wetness_color(vec3 baseColor, float wetness) {
    return mix(baseColor, baseColor * vec3(0.92, 0.95, 1.00), wetness * 0.35);
}

// ===================== Subsurface Scattering =====================

vec3 pbr_sss_contribution(vec3 N, vec3 V, vec3 L, vec3 radiance,
                          vec3 albedo, float sss, float roughness) {
    float NdotL = max(dot(N, L), 0.0);
    float wrap = max(NdotL + sss, 0.001) / max(1.0 + sss, 0.001);
    float sssAmount = wrap * wrap;
    return albedo * radiance * sssAmount * sss * 0.5;
}

// ===================== Procedural Normal Generation =====================

vec3 pbr_generate_normal(sampler2D tex, vec2 uv, vec3 pos, float strength) {
    vec2 texelSize = 1.0 / vec2(textureSize(tex, 0));
    float hL = texture(tex, uv + vec2(-texelSize.x, 0.0)).r;
    float hR = texture(tex, uv + vec2(texelSize.x, 0.0)).r;
    float hD = texture(tex, uv + vec2(0.0, -texelSize.y)).r;
    float hU = texture(tex, uv + vec2(0.0, texelSize.y)).r;

    vec3 dx = dFdx(pos);
    vec3 dy = dFdy(pos);

    vec3 grad = (hR - hL) * dx + (hU - hD) * dy;
    float len = length(grad);
    if (len < 1e-8) return normalize(cross(dx, dy));

    vec3 faceNormal = normalize(cross(dx, dy));
    vec3 bumped = normalize(faceNormal - grad * strength);
    return normalize(mix(faceNormal, bumped, strength));
}

// ===================== Debug Visualization =====================

vec3 pbr_debug_visualize(int mode, vec3 albedo, float roughness, float metallic, float ao,
                         vec3 emissiveColor, uint materialClass, vec3 normal,
                         float skyLight, float blockLight, vec3 finalLitColor,
                         float shadowFactor, vec3 specularColor, float NdotL, uint rawPackedBits) {
    if (mode == 1) return vec3(blockLight);
    if (mode == 2) return vec3(skyLight);
    if (mode == 3) return vec3(ao);
    if (mode == 4) return vec3(shadowFactor);
    if (mode == 5) return vec3(roughness);
    if (mode == 6) return vec3(metallic);
    if (mode == 7) return vec3(float(materialClass) / 11.0);
    if (mode == 8) return specularColor;
    if (mode == 9) return finalLitColor;
    if (mode == 10) return albedo;
    if (mode == 11) return vec3(NdotL);
    if (mode == 12) {
        uint raw = rawPackedBits;
        uint b0_3 = raw & 0xFu;
        uint b4_7 = (raw >> 4) & 0xFu;
        uint b8_11 = (raw >> 8) & 0xFu;
        return vec3(float(b8_11) / 15.0, float(b4_7) / 15.0, float(b0_3) / 15.0);
    }
    return vec3(metallic, roughness, ao);
}

#endif
