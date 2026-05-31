#version 450

layout(binding = 1) uniform sampler2D Sampler0;
layout(binding = 2) uniform sampler2D Sampler1;

layout(binding = 0) uniform UBO {
    vec2 ScreenSize;
    mat4 InverseProjMat;
    float SkyDayMoment;
    float SkyDayMixer;
    float SkyNightMixer;
    float SkyRainStrength;
    float dayNightMix;
    int isEyeInWater;
};

layout(location = 0) out vec4 fragColor;

const float WATER_ABSORPTION = 0.10;
const vec3 LIGHT_SUNSET_COLOR = vec3(1.0, 0.59, 0.35);
const vec3 LIGHT_DAY_COLOR = vec3(0.90, 0.84, 0.79);
const vec3 LIGHT_NIGHT_COLOR = vec3(0.05, 0.05, 0.06);

const vec3 WATER_SCATTER_COLOR = vec3(0.08, 0.35, 0.42);
const vec3 LAVA_SCATTER_COLOR = vec3(1.0, 0.3, 0.05);
const vec3 POWDER_SNOW_SCATTER_COLOR = vec3(0.75, 0.85, 0.6);

vec3 dayBlend(vec3 sunset, vec3 day, vec3 night) {
    vec3 dayColor = mix(sunset, day, SkyDayMixer);
    vec3 nightColor = mix(sunset, night, SkyNightMixer);
    return mix(dayColor, nightColor, step(0.5, SkyDayMoment));
}

vec3 reconstructViewPosition(vec2 uv, float depth) {
    vec2 ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    vec4 clip = vec4(ndc, depth * 2.0 - 1.0, 1.0);
    vec4 view = InverseProjMat * clip;
    return view.xyz / max(view.w, 0.00001);
}

float underwaterFogAmount(float depth, vec3 viewPos) {
    if (depth >= 0.9999) {
        return 1.0;
    }
    float distanceToSurface = length(viewPos);
    float density = 0.015 + WATER_ABSORPTION * 0.045;
    return clamp(1.0 - exp(-distanceToSurface * density), 0.0, 1.0);
}

float schlickPhase(float cosTheta, float g) {
    float gg = g * g;
    return (1.0 - gg) / (4.0 * 3.14159 * pow(1.0 + gg - 2.0 * g * cosTheta, 1.5));
}

float waterSunScattering(vec3 viewPos, vec3 lightDir) {
    vec3 viewDir = normalize(viewPos);
    float cosAngle = dot(viewDir, -lightDir);
    float phase = schlickPhase(cosAngle, 0.6);
    float distanceFactor = 1.0 - exp(-length(viewPos) * 0.02);
    return phase * distanceFactor * 0.5;
}

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec4 color = texture(Sampler0, uv);
    float depth = texture(Sampler1, uv).r;

    if (isEyeInWater == 1) {
        vec3 viewPos = reconstructViewPosition(uv, depth);
        float fogAmount = underwaterFogAmount(depth, viewPos);
        vec3 directLightColor = dayBlend(LIGHT_SUNSET_COLOR, LIGHT_DAY_COLOR, LIGHT_NIGHT_COLOR);
        vec3 waterFogColor = WATER_SCATTER_COLOR * mix(vec3(0.55), directLightColor, 0.55);
        waterFogColor = mix(waterFogColor, waterFogColor * 0.85, SkyRainStrength * 0.35);

        vec3 sunDir = normalize(vec3(0.0, 1.0, 0.0));
        float sunScatter = waterSunScattering(viewPos, sunDir);
        waterFogColor += directLightColor * sunScatter * sunScatter;

        vec3 surfaceColor = mix(color.rgb, waterFogColor, fogAmount);
        float depthFade = clamp(length(viewPos) * 0.008, 0.0, 1.0);
        vec3 absorptionColor = WATER_SCATTER_COLOR * 0.3;
        surfaceColor = mix(surfaceColor, absorptionColor, depthFade * 0.3);
        color.rgb = surfaceColor;
    } else if (isEyeInWater == 2) {
        float fogAmount = clamp(sqrt(max(depth, 0.0)), 0.0, 1.0);
        float heatHaze = sin(gl_FragCoord.y * 0.1 + SkyDayMoment * 20.0) * 0.02;
        vec3 lavaColor = LAVA_SCATTER_COLOR + vec3(heatHaze, heatHaze * 0.5, 0.0);
        color.rgb = mix(color.rgb, lavaColor, fogAmount * 0.8);
    } else if (isEyeInWater == 3) {
        float fogAmount = depth >= 0.9999 ? 1.0 : clamp(length(reconstructViewPosition(uv, depth)) * 0.05, 0.0, 1.0);
        vec3 snowColor = POWDER_SNOW_SCATTER_COLOR + vec3(0.1);
        color.rgb = mix(color.rgb, snowColor, fogAmount * 0.7);
    }

    fragColor = color;
}
