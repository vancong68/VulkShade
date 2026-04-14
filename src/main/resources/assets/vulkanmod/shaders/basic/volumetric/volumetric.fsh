#version 450

layout(binding = 1) uniform sampler2D Sampler0;
layout(binding = 2) uniform sampler2D Sampler1;
layout(binding = 3) uniform sampler2D Sampler5;

layout(binding = 0) uniform UBO {
    vec2 ScreenSize;
    mat4 InverseProjMat;
    mat4 InverseModelViewMat;
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
    float near;
    float far;
    int moonPhase;
    int isEyeInWater;
};

#include "makeup_lighting.glsl"

layout(location = 0) out vec4 fragColor;

const int GODRAY_STEPS = 6;

vec3 reconstructViewPosition(vec2 uv, float depth) {
    vec2 ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    vec4 clip = vec4(ndc, depth * 2.0 - 1.0, 1.0);
    vec4 view = InverseProjMat * clip;
    return view.xyz / max(view.w, 0.00001);
}

float volumetricDayMixer() {
    float momentAux5 = (dayMoment * 4.0) - 1.0;
    float momentAux6 = momentAux5 * momentAux5;
    momentAux6 *= momentAux6;
    float dayVolMixer = clamp(((-momentAux6 + 1.0) * 7.0) + 1.0, 1.0, 8.0);

    float momentAux7 = (dayMoment * 4.0) - 3.0;
    float momentAux8 = momentAux7 * momentAux7;
    momentAux8 *= momentAux8;
    float nightVolMixer = clamp(((-momentAux8 + 1.0) * 7.0) + 1.0, 1.0, 8.0);

    return max(dayVolMixer, nightVolMixer);
}

vec3 volumetricLightColor() {
    float attenuation = isEyeInWater == 0 ? 1.0 : 0.25;
    return makeup_day_blend(
        MAKEUP_LIGHT_SUNSET_COLOR,
        MAKEUP_LIGHT_DAY_COLOR,
        makeup_light_night_color()
    ) * 1.2 * attenuation;
}

float sampleShadowLight(vec3 worldPos) {
    vec3 shadowPos = makeup_shadow_pos(worldPos);
    if (shadowPos.x <= 0.0 || shadowPos.x >= 1.0 || shadowPos.y <= 0.0 || shadowPos.y >= 1.0 || shadowPos.z <= 0.0 || shadowPos.z >= 1.0) {
        return 0.0;
    }

    return makeup_shadow(shadowPos, 0.0);
}

float traceVolumetricLight(vec3 rayDirView, float maxDistance) {
    float dither = makeup_interleaved_gradient_noise(gl_FragCoord.xy);
    float light = 0.0;
    float distributionDenominator = exp2(float(GODRAY_STEPS)) - 1.0;

    for (int i = 0; i < GODRAY_STEPS; ++i) {
        float t = (exp2(float(i) + dither) - 1.0) / distributionDenominator;
        float sampleDistance = clamp(t, 0.0, 1.0) * maxDistance;
        vec3 sampleViewPos = rayDirView * sampleDistance;
        vec3 sampleWorldPos = (InverseModelViewMat * vec4(sampleViewPos, 1.0)).xyz;
        light += sampleShadowLight(sampleWorldPos);
    }

    light /= float(GODRAY_STEPS);
    return light * light;
}

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec4 sceneColor = texture(Sampler0, uv);
    float depth = texture(Sampler1, uv).r;
    vec3 sunDir = normalize(sunPosition);
    float sunVisibility = smoothstep(-0.12, 0.02, sunDir.y);

    // Keep the sky itself on the original sky pass. The volumetric composite
    // should add shafts across the rendered scene, not replace the sun with a
    // giant fullscreen halo.
    if (depth >= 0.9999 || dayNightMix <= 0.0 || sunVisibility <= 0.0) {
        fragColor = sceneColor;
        return;
    }

    vec3 visibleViewPos = reconstructViewPosition(uv, min(depth, 0.99999));
    vec3 rayDirView = normalize(reconstructViewPosition(uv, 1.0));

    float maxDistance = depth >= 0.9999 ? far * 0.5 : min(length(visibleViewPos), far * 0.5);
    maxDistance = max(maxDistance, near * 4.0);

    float volumetricLight = traceVolumetricLight(rayDirView, maxDistance);
    vec3 worldEyeDirection = normalize((InverseModelViewMat * vec4(rayDirView, 0.0)).xyz);
    float intensity = dot(worldEyeDirection, sunDir);
    intensity = pow(clamp((intensity + 0.5) * 0.6666666666666666, 0.0, 1.0), volumetricDayMixer());
    intensity *= 0.6 * dayNightMix * sunVisibility;
    intensity *= (1.0 - rainStrength);

    float blend = clamp(intensity * (volumetricLight * 0.5 + 0.5), 0.0, 0.45);
    sceneColor.rgb = mix(sceneColor.rgb, volumetricLightColor() * volumetricLight, blend);

    fragColor = sceneColor;
}
