#ifndef PBR_GLSL
#define PBR_GLSL

const float PBR_PI = 3.14159265359;

// ===================== LabPBR Texture Decoding =====================

struct LabPBRSpecular {
    float perceptualSmoothness;
    float roughness;
    float f0;            // 0-0.9 range for non-metals
    float metalFactor;   // 0 = dielectric, 1 = metal
    int metalID;         // 0 = none, 230-255 = predefined metal
    float sss;           // subsurface scattering 0-1
    float porosity;      // porosity 0-1
    float emissive;      // emissive 0-1
};

struct LabPBRNormal {
    vec3 normal;         // world-space normal after decoding + transformation
    float ao;            // ambient occlusion 0-1
    float height;        // height/displacement 0-1 (0 = deep, 1 = shallow)
};

// Predefined metal IOR (n) and extinction (k) from LabPBR spec
const vec3 PBR_METAL_N[8] = vec3[](
    vec3(2.9114, 2.9497, 2.5845),   // 230 Iron
    vec3(0.18299, 0.42108, 1.3734), // 231 Gold
    vec3(1.3456, 0.96521, 0.61722), // 232 Aluminum
    vec3(3.1071, 3.1812, 2.3230),   // 233 Chrome
    vec3(0.27105, 0.67693, 1.3164), // 234 Copper
    vec3(1.9100, 1.8300, 1.4400),   // 235 Lead
    vec3(2.3757, 2.0847, 1.8453),   // 236 Platinum
    vec3(0.15943, 0.14512, 0.13547) // 237 Silver
);

const vec3 PBR_METAL_K[8] = vec3[](
    vec3(3.0893, 2.9318, 2.7670),   // 230 Iron
    vec3(3.4242, 2.3459, 1.7704),   // 231 Gold
    vec3(7.4746, 6.3995, 5.3031),   // 232 Aluminum
    vec3(3.3314, 3.3291, 3.1350),   // 233 Chrome
    vec3(3.6092, 2.6248, 2.2921),   // 234 Copper
    vec3(3.5100, 3.4000, 3.1800),   // 235 Lead
    vec3(4.2655, 3.7153, 3.1365),   // 236 Platinum
    vec3(3.9291, 3.1900, 2.3808)    // 237 Silver
);

LabPBRSpecular pbr_decode_specular(vec4 specularSample) {
    LabPBRSpecular s;
    s.perceptualSmoothness = specularSample.r;
    s.roughness = pow(1.0 - s.perceptualSmoothness, 2.0);
    s.roughness = clamp(s.roughness, 0.001, 0.999);

    float green = specularSample.g;
    if (green < 0.902) {
        s.f0 = green * (0.04 / 0.902);
        s.metalFactor = 0.0;
        s.metalID = 0;
    } else if (green >= 0.902) {
        int id = int(round(green * 255.0));
        if (id >= 230 && id <= 237) {
            s.metalID = id;
            s.metalFactor = 1.0;
            int idx = id - 230;
            vec3 n = PBR_METAL_N[idx];
            vec3 k = PBR_METAL_K[idx];
            vec3 f0 = ((n - 1.0) * (n - 1.0) + k * k) / ((n + 1.0) * (n + 1.0) + k * k);
            s.f0 = max(max(f0.r, f0.g), f0.b);
        } else {
            s.metalID = 255;
            s.metalFactor = 1.0;
            s.f0 = 1.0;
        }
    } else {
        s.metalID = 0;
        s.metalFactor = 0.0;
        s.f0 = 0.04;
    }

    float blue = specularSample.b;
    if (blue < 0.251) {
        s.porosity = blue * 4.0;
        s.sss = 0.0;
    } else {
        s.porosity = 0.0;
        s.sss = (blue - 0.251) / 0.749;
    }

    s.emissive = specularSample.a;
    return s;
}

LabPBRNormal pbr_decode_normal(vec4 normalSample) {
    LabPBRNormal n;
    vec3 decoded = normalSample.rgb * 2.0 - 1.0;
    decoded.y = -decoded.y;
    float lenSq = dot(decoded.xy, decoded.xy);
    if (lenSq > 0.001) {
        decoded.z = sqrt(max(1.0 - lenSq, 0.0));
        n.normal = normalize(decoded);
    } else {
        n.normal = vec3(0.0, 0.0, 1.0);
    }
    n.ao = normalSample.b;
    n.height = normalSample.a;
    if (n.height >= 0.999) n.height = 0.0;
    return n;
}

// ===================== Parallax Occlusion Mapping =====================

vec2 pbr_parallax_mapping(sampler2D normalTex, vec2 uv, vec3 viewDirTS, float heightScale) {
    const int POM_LAYERS = 16;
    float layerDepth = 1.0 / float(POM_LAYERS);
    float currentDepth = 0.0;
    vec2 deltaUV = viewDirTS.xy * heightScale / (viewDirTS.z * float(POM_LAYERS));

    if (any(isnan(deltaUV)) || any(isinf(deltaUV))) return uv;

    vec2 currentUV = uv;
    float currentHeight = texture(normalTex, currentUV).a;

    for (int i = 0; i < POM_LAYERS; ++i) {
        currentDepth += layerDepth;
        currentUV -= deltaUV;
        currentUV = clamp(currentUV, 0.0, 1.0);
        currentHeight = texture(normalTex, currentUV).a;
        if (currentDepth > currentHeight) {
            float prevHeight = texture(normalTex, clamp(uv - deltaUV * float(i), 0.0, 1.0)).a;
            float depthDiff = currentDepth - currentHeight;
            float prevDiff = currentDepth - layerDepth - prevHeight;
            float t = depthDiff / max(depthDiff - prevDiff, 0.0001);
            return clamp(uv - deltaUV * (float(i) - t) * 0.25, 0.0, 1.0);
        }
    }
    return clamp(currentUV, 0.0, 1.0);
}

// ===================== PBR BRDF Functions =====================

float pbr_distribution_ggx(vec3 N, vec3 H, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;
    float denom = NdotH2 * (a2 - 1.0) + 1.0;
    return a2 / (PBR_PI * denom * denom);
}

float pbr_geometry_schlick_ggx(float NdotV, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return NdotV / (NdotV * (1.0 - k) + k);
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
    vec3 H = normalize(V + L);
    float NDF = pbr_distribution_ggx(N, H, roughness);
    float G = pbr_geometry_smith(N, V, L, roughness);
    vec3 F = pbr_fresnel_schlick(max(dot(H, V), 0.0), F0);

    vec3 kS = F;
    vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);
    float NdotL = max(dot(N, L), 0.0);

    vec3 specular = NDF * G * F / max(4.0 * max(dot(N, V), 0.0) * NdotL, 0.001);
    return (kD * albedo / PBR_PI + specular) * radiance * NdotL;
}

vec3 pbr_f0_for_dielectric(float f0_value) {
    return vec3(f0_value);
}

vec3 pbr_f0_for_metal(vec3 albedo, int metalID) {
    if (metalID >= 230 && metalID <= 237) {
        int idx = metalID - 230;
        vec3 n = PBR_METAL_N[idx];
        vec3 k = PBR_METAL_K[idx];
        return ((n - 1.0) * (n - 1.0) + k * k) / ((n + 1.0) * (n + 1.0) + k * k);
    }
    return albedo;
}

vec3 pbr_emissive_contribution(float emissive, vec3 emissiveColor) {
    return emissive * emissiveColor;
}

float pbr_wetness_roughness(float roughness, float wetness) {
    return clamp(mix(roughness, max(roughness * 0.35, 0.05), wetness), 0.001, 0.999);
}

vec3 pbr_wetness_color(vec3 baseColor, float wetness) {
    return mix(baseColor, baseColor * vec3(0.92, 0.95, 1.00), wetness * 0.35);
}

vec3 pbr_debug_visualize(int mode, vec3 albedo, vec3 F0, float roughness, float metallic, float ao, vec3 emissiveColor) {
    if (mode == 1) return vec3(roughness);
    if (mode == 2) return vec3(metallic);
    if (mode == 3) return F0;
    if (mode == 4) return vec3(ao);
    if (mode == 5) return emissiveColor;
    if (mode == 6) return albedo;
    return vec3(metallic, roughness, ao);
}

// ===================== World Space Normal Map Conversion =====================

mat3 pbr_tangent_to_world(vec3 worldNormal, vec3 worldPos, vec2 uv) {
    vec3 dp1 = dFdx(worldPos);
    vec3 dp2 = dFdy(worldPos);
    vec2 duv1 = dFdx(uv);
    vec2 duv2 = dFdy(uv);
    float det = duv1.x * duv2.y - duv1.y * duv2.x;
    if (abs(det) < 1e-6 || any(isnan(dp1)) || any(isinf(dp1))) return mat3(1.0);
    float signDet = sign(det);
    vec3 T = dp1 * duv2.y - dp2 * duv1.y;
    vec3 B = dp2 * duv1.x - dp1 * duv2.x;
    float tLen = length(T);
    float bLen = length(B);
    if (tLen < 1e-6 || bLen < 1e-6 || isnan(tLen) || isinf(tLen)) return mat3(1.0);
    T /= tLen;
    B /= bLen;
    vec3 N = worldNormal;
    T = normalize(T - dot(T, N) * N);
    B = cross(N, T) * signDet;
    return mat3(T, B, N);
}

vec3 pbr_apply_normal_map(vec3 worldNormal, vec3 tangentNormal, vec3 worldPos, vec2 uv) {
    mat3 TBN = pbr_tangent_to_world(worldNormal, worldPos, uv);
    vec3 result = TBN * tangentNormal;
    if (any(isnan(result)) || any(isinf(result))) return worldNormal;
    return normalize(result);
}

// ===================== Subsurface Scattering Approximation =====================

vec3 pbr_sss_contribution(vec3 N, vec3 V, vec3 L, vec3 radiance,
                          vec3 albedo, float sss, float roughness) {
    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 0.0);
    float wrap = 0.5 + 0.5 * sss;
    float sssAmount = pow(max(NdotL + sss, 0.0) / (1.0 + sss), 2.0);
    return albedo * radiance * sssAmount * sss * 0.5;
}

#endif
