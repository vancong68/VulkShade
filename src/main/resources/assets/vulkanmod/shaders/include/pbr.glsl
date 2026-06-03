#ifndef PBR_GLSL
#define PBR_GLSL

const float PBR_PI = 3.14159265359;

// ===================== Material Structures =====================

struct PBRMaterial {
    vec3  albedo;
    float roughness;
    float metallic;
    float ao;
    vec3  emissive;
    float perceptualRoughness;
    vec3  worldNormal;
};

struct PBRLight {
    vec3  direction;
    vec3  radiance;
    float attenuation;
};

// ===================== Gamma / Color Space =====================

vec3 pbr_srgb_to_linear(vec3 srgb) {
    vec3 mask = vec3(greaterThan(srgb, vec3(0.04045)));
    return mix(srgb / 12.92, pow((srgb + 0.055) / 1.055, vec3(2.4)), mask);
}

vec3 pbr_linear_to_srgb(vec3 linear) {
    vec3 mask = vec3(greaterThan(linear, vec3(0.0031308)));
    return mix(linear * 12.92, 1.055 * pow(max(linear, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055, mask);
}

// ===================== TBN Construction from Screen-Space Derivatives =====================

mat3 pbr_construct_tbn(vec3 worldPos, vec2 uv, vec3 worldNormal) {
    vec3 dPdx = dFdx(worldPos);
    vec3 dPdy = dFdy(worldPos);
    vec2 dUdx = dFdx(uv);
    vec2 dUdy = dFdy(uv);

    float det = dUdx.x * dUdy.y - dUdx.y * dUdy.x;
    if (abs(det) < 1e-6) return mat3(1.0);

    vec3 T = normalize(dUdy.y * dPdx - dUdx.y * dPdy) * sign(det);
    vec3 B = normalize(-dUdy.x * dPdx + dUdx.x * dPdy) * sign(det);

    T = normalize(T - worldNormal * dot(T, worldNormal));
    B = normalize(cross(worldNormal, T));

    return mat3(T, B, worldNormal);
}

// ===================== Normal Map Sampling =====================

vec3 pbr_decode_normal(vec2 rg) {
    vec3 n;
    n.xy = rg * 2.0 - 1.0;
    n.z = sqrt(max(1.0 - dot(n.xy, n.xy), 0.0));
    return n;
}

vec3 pbr_sample_normal(sampler2D normalMap, vec2 uv) {
    return pbr_decode_normal(texture(normalMap, uv).rg);
}

// ===================== Parallax Occlusion Mapping =====================

vec2 pbr_parallax_occlusion_mapping(
    sampler2D heightMap, vec2 texCoord, vec3 viewDirTS,
    float heightScale, float numLayers
) {
    float layerHeight = 1.0 / max(numLayers, 1.0);
    vec2 deltaTexCoord = (viewDirTS.xy / max(abs(viewDirTS.z), 0.001)) * heightScale / numLayers;

    vec2 currentTexCoord = texCoord;
    float currentHeight = texture(heightMap, currentTexCoord).r;
    float currentLayerHeight = 1.0;

    for (int i = 0; i < 64; ++i) {
        if (currentLayerHeight <= currentHeight) break;
        currentTexCoord -= deltaTexCoord;
        currentHeight = texture(heightMap, currentTexCoord).r;
        currentLayerHeight -= layerHeight;
    }

    vec2 prevTexCoord = currentTexCoord + deltaTexCoord;
    float afterHeight = currentHeight - currentLayerHeight;
    float beforeHeight = texture(heightMap, prevTexCoord).r - (currentLayerHeight + layerHeight);
    float weight = afterHeight / max(afterHeight - beforeHeight, 0.0001);

    return mix(currentTexCoord, prevTexCoord, clamp(weight, 0.0, 1.0));
}

vec2 pbr_apply_pom(sampler2D heightMap, vec2 texCoord, vec3 V_ts, float heightScale, float fragDistance) {
    if (heightScale <= 0.0 || fragDistance > 64.0) return texCoord;

    float distFactor = clamp(1.0 - fragDistance / 64.0, 0.0, 1.0);
    float numLayers = mix(8.0, 32.0, distFactor * distFactor);
    return pbr_parallax_occlusion_mapping(heightMap, texCoord, V_ts, heightScale, numLayers);
}

// ===================== BRDF Functions =====================

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

vec3 pbr_fresnel_schlick_roughness(float cosTheta, vec3 F0, float roughness) {
    return F0 + (max(vec3(1.0 - roughness), F0) - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

// ===================== Cook-Torrance BRDF =====================

vec3 pbr_cook_torrance(
    vec3 N, vec3 V, vec3 L,
    vec3 radiance, vec3 F0,
    float roughness, vec3 albedo, float metallic
) {
    float NdotV = max(dot(N, V), 0.001);
    float NdotL = max(dot(N, L), 0.0);
    if (NdotL <= 0.0) return vec3(0.0);

    vec3 H = normalize(V + L);

    float NDF = pbr_distribution_ggx(N, H, roughness);
    float G   = pbr_geometry_smith(N, V, L, roughness);
    vec3  F   = pbr_fresnel_schlick(max(dot(H, V), 0.0), F0);

    vec3 kS = F;
    vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);
    float denom = max(4.0 * NdotV * NdotL, 0.001);

    vec3 specular = NDF * G * F / max(denom, 0.001);
    return (kD * albedo / PBR_PI + specular) * radiance * NdotL;
}

// ===================== PBR Evaluate (Single Direct Light) =====================

vec3 pbr_evaluate(
    PBRMaterial material, PBRLight light,
    vec3 N, vec3 V
) {
    vec3 F0 = mix(vec3(0.04), material.albedo, material.metallic);
    vec3 L = normalize(light.direction);

    vec3 direct = pbr_cook_torrance(
        N, V, L,
        light.radiance * light.attenuation,
        F0, material.roughness,
        material.albedo, material.metallic
    );

    return direct;
}

// ===================== IBL Approximation (Lightmap-Based) =====================
// Uses lightmap as ambient proxy while subtracting sun component to avoid
// double-counting with the direct Cook-Torrance BRDF.

vec3 pbr_evaluate_ibl_approx(
    PBRMaterial mat, vec3 N, vec3 V,
    vec2 rawLightLevels, float dayNightMix
) {
    vec3 F0 = mix(vec3(0.04), mat.albedo, mat.metallic);
    float NdotV = max(dot(N, V), 0.0);
    vec3 F = pbr_fresnel_schlick_roughness(NdotV, F0, mat.roughness);
    vec3 kS = F;
    vec3 kD = (vec3(1.0) - kS) * (1.0 - mat.metallic);

    float blockLight = rawLightLevels.x;
    float skyLight = rawLightLevels.y;

    float ambientSky = skyLight * (1.0 - dayNightMix * 0.85);
    float ambient = max(ambientSky, blockLight);
    ambient = max(ambient, 0.01);

    vec3 irradiance = vec3(ambient * 0.06);

    vec3 diffuse = kD * mat.albedo * irradiance * mat.ao;

    float envBRDF = (1.0 - mat.roughness) * (1.0 - mat.roughness);
    vec3 specular = F * irradiance * envBRDF * mat.ao;

    return diffuse + specular;
}

// ===================== Full IBL with Environment Maps =====================

vec3 pbr_evaluate_ibl(
    vec3 N, vec3 V, vec3 albedo,
    float roughness, float metallic, float ao,
    vec3 irradiance, vec3 prefiltered, vec2 brdfLUT
) {
    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    vec3 F = pbr_fresnel_schlick_roughness(max(dot(N, V), 0.0), F0, roughness);
    vec3 kS = F;
    vec3 kD = (1.0 - kS) * (1.0 - metallic);

    vec3 diffuse = albedo * irradiance;
    vec3 specular = prefiltered * (F * brdfLUT.x + brdfLUT.y);

    return (kD * diffuse + specular) * ao;
}

// ===================== Material Construction =====================

PBRMaterial pbr_material_from_textures(
    vec3 baseColorLinear, sampler2D normalMap, sampler2D specularMap, sampler2D aoMap,
    vec2 uv, mat3 TBN, float normalStrength
) {
    PBRMaterial mat;

    vec4 specTex = texture(specularMap, uv);
    float ao = texture(aoMap, uv).r;

    mat.albedo = baseColorLinear;
    mat.roughness = clamp(1.0 - specTex.g, 0.02, 0.98);
    mat.metallic = specTex.b;
    mat.ao = clamp(ao, 0.0, 1.0);
    mat.emissive = vec3(0.0);
    mat.perceptualRoughness = mat.roughness;

    vec3 tangentNormal = pbr_sample_normal(normalMap, uv);
    float strength = clamp(normalStrength, 0.0, 1.0);
    mat.worldNormal = normalize(mix(TBN[2], TBN * tangentNormal, strength));

    return mat;
}

// ===================== Encode/Decode =====================

vec3 pbr_encode_normal(vec3 n) {
    return n * 0.5 + 0.5;
}

vec3 pbr_decode_normal(vec3 n) {
    return n * 2.0 - 1.0;
}

float pbr_luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

// ===================== Tone Mapping =====================
// These are available for direct use. When post-process ACES is active,
// do NOT call these from the fragment shader — output linear HDR instead.

vec3 pbr_aces_tone_map(vec3 color) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    return clamp((color * (a * color + b)) / (color * (c * color + d) + e), 0.0, 1.0);
}

vec3 pbr_reinhard_tone_map(vec3 color) {
    return color / (color + 1.0);
}

// ===================== Debug modes =====================

vec3 pbr_debug_view(int mode, PBRMaterial mat, vec3 N, vec3 litColor, float NdotL) {
    if (mode == 1) return pbr_linear_to_srgb(mat.albedo);
    if (mode == 2) return vec3(mat.roughness);
    if (mode == 3) return vec3(mat.metallic);
    if (mode == 4) return vec3(mat.ao);
    if (mode == 5) return mat.emissive;
    if (mode == 6) return pbr_encode_normal(N);
    if (mode == 7) return vec3(NdotL);
    if (mode == 8) return litColor;
    return vec3(mat.metallic, mat.roughness, mat.ao);
}

#endif
