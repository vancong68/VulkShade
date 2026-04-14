#version 450

layout(early_fragment_tests) in;

#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler0;
layout(binding = 4) uniform sampler2D Sampler3;
layout(binding = 5) uniform sampler2D Sampler4;
layout(binding = 6) uniform sampler2D Sampler5;
layout(binding = 7) uniform sampler2D Sampler6;
layout(binding = 8) uniform sampler2D Sampler7;

layout(binding = 1) uniform UBO {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
    float AlphaCutout;
    float near;
    float far;
    mat4 ModelViewMat;
    mat4 ProjMat;
    mat4 InverseProjMat;
    vec3 SkyCameraPosition;
    vec3 SkySunDirection;
    float SkyCloudTime;
    float SkyDayMoment;
    float SkyDayMixer;
    float SkyNightMixer;
    float SkyRainStrength;
    vec3 shadowLightPosition;
    mat4 shadowModelView;
    mat4 shadowProjection;
    float dayNightMix;
    int moonPhase;
    int isEyeInWater;
    int terrainShadowQuality;
    int terrainLightingQuality;
    int waterQuality;
};

// Aliases for makeup_lighting.glsl compatibility
#define cameraPosition SkyCameraPosition
#define sunPosition SkySunDirection
#define dayMoment SkyDayMoment
#define dayMixer SkyDayMixer
#define nightMixer SkyNightMixer
#define rainStrength SkyRainStrength
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

const uint MATERIAL_WATER = 0x1u;
const uint MATERIAL_WATER_SURFACE = 0x2u;

const float NIGHT_BRIGHT = 0.60;
const float WATER_TURBULENCE = 0.85;

const vec3 WORLD_UP = vec3(0.0, 1.0, 0.0);

const vec3 ZENITH_SUNSET_COLOR = vec3(0.143, 0.24394118, 0.36450981);
const vec3 ZENITH_DAY_COLOR = vec3(0.143, 0.24394118, 0.36450981);
const vec3 ZENITH_NIGHT_COLOR = vec3(0.014, 0.019, 0.025) * NIGHT_BRIGHT;

const vec3 HORIZON_SUNSET_COLOR = vec3(1.0, 0.648, 0.37824);
const vec3 HORIZON_DAY_COLOR = vec3(0.65, 0.91, 1.3);
const vec3 HORIZON_NIGHT_COLOR = vec3(0.0213, 0.0306, 0.0387) * NIGHT_BRIGHT;

const vec3 ZENITH_SKY_RAIN_COLOR = vec3(0.7, 0.85, 1.0);
const vec3 HORIZON_SKY_RAIN_COLOR = vec3(0.35, 0.425, 0.5);

const vec3 LIGHT_SUNSET_COLOR = vec3(1.0, 0.59, 0.35);
const vec3 LIGHT_DAY_COLOR = vec3(0.90, 0.84, 0.79);
const vec3 LIGHT_NIGHT_COLOR = vec3(0.05, 0.05, 0.06);

const float CLOUD_PLANE_SUP = 590.0;
const float CLOUD_PLANE_CENTER = 375.0;
const float CLOUD_PLANE = 319.0;
const int CLOUD_STEPS_AVG = 10;
const float CLOUD_WORLD_SCALE = 0.0002777777777777778;
const float CLOUD_HI_FACTOR = 0.0016666666666666666;
const int WATER_RAYMARCH_STEPS = 7;
const float WATER_ABSORPTION = 0.10;
const float WATER_REFLEX_INDEX = 0.70;
const vec3 WATER_COLOR = vec3(0.05, 0.1, 0.11);

float squarePow(float x) {
    return x * x;
}

float luma(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

vec3 rgbToXyz(vec3 rgb) {
    vec3 mask = vec3(greaterThan(rgb, vec3(0.04045)));
    vec3 linear = mix(rgb / 12.92, pow((rgb + 0.055) / 1.055, vec3(2.4)), mask);

    const mat3 rgbToXyzMatrix = mat3(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041
    );

    return rgbToXyzMatrix * linear;
}

vec3 xyzToRgb(vec3 xyz) {
    const mat3 xyzToRgbMatrix = mat3(
        3.2404542, -1.5371385, -0.4985314,
       -0.9692660,  1.8760108,  0.0415560,
        0.0556434, -0.2040259,  1.0572252
    );

    vec3 rgb = xyzToRgbMatrix * xyz;
    vec3 mask = vec3(greaterThan(rgb, vec3(0.0031308)));
    rgb = mix(12.92 * rgb, 1.055 * pow(max(rgb, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055, mask);
    return clamp(rgb, 0.0, 1.0);
}

vec3 dayBlend(vec3 sunset, vec3 day, vec3 night) {
    vec3 dayColor = mix(sunset, day, SkyDayMixer);
    vec3 nightColor = mix(sunset, night, SkyNightMixer);
    return mix(dayColor, nightColor, step(0.5, SkyDayMoment));
}

float interleavedGradientNoise(vec2 coord) {
    return fract(52.9829189 * fract(0.06711056 * coord.x + 0.00583715 * coord.y));
}

float rainThreshold() {
    float invRain = clamp(1.0 - SkyRainStrength, 0.0, 1.0);
    float smoothedInvRain = invRain * invRain * (3.0 - 2.0 * invRain);
    return smoothedInvRain * 0.3 + 0.25;
}

vec3 buildSkyBase(vec3 rayDir) {
    float n_u = clamp(dot(rayDir, WORLD_UP), 0.0, 1.0);

    vec3 zenithSkyColor = rgbToXyz(dayBlend(
        ZENITH_SUNSET_COLOR,
        ZENITH_DAY_COLOR,
        ZENITH_NIGHT_COLOR
    ));
    zenithSkyColor = mix(
        zenithSkyColor,
        ZENITH_SKY_RAIN_COLOR * luma(zenithSkyColor),
        SkyRainStrength
    );

    vec3 horizonSkyColor = rgbToXyz(dayBlend(
        HORIZON_SUNSET_COLOR,
        HORIZON_DAY_COLOR,
        HORIZON_NIGHT_COLOR
    ));
    horizonSkyColor = mix(
        horizonSkyColor,
        HORIZON_SKY_RAIN_COLOR * luma(horizonSkyColor),
        SkyRainStrength
    );

    vec3 skyColor = xyzToRgb(mix(horizonSkyColor, zenithSkyColor, smoothstep(0.0, 1.0, pow(n_u, 0.333))));
    float belowHorizon = clamp(-dot(rayDir, WORLD_UP) * 3.0, 0.0, 1.0);
    return mix(skyColor, skyColor * 0.15, belowHorizon);
}

void getCloudColors(out vec3 cloudColor, out vec3 darkCloudColor) {
    darkCloudColor = dayBlend(
        ZENITH_SUNSET_COLOR,
        ZENITH_DAY_COLOR,
        ZENITH_NIGHT_COLOR
    );
    darkCloudColor = mix(
        darkCloudColor,
        ZENITH_SKY_RAIN_COLOR * luma(darkCloudColor),
        SkyRainStrength
    );

    vec3 cloudColorAux = mix(
        dayBlend(
            LIGHT_SUNSET_COLOR,
            LIGHT_DAY_COLOR,
            LIGHT_NIGHT_COLOR * vec3(0.5, 0.6, 0.75)
        ),
        ZENITH_SKY_RAIN_COLOR * luma(darkCloudColor),
        SkyRainStrength
    );

    cloudColor = mix(
        clamp(mix(vec3(luma(cloudColorAux)), cloudColorAux, 0.5) * vec3(1.5), 0.0, 1.4),
        dayBlend(HORIZON_SUNSET_COLOR, HORIZON_DAY_COLOR, HORIZON_NIGHT_COLOR),
        0.3
    );
    cloudColor = mix(
        cloudColor,
        HORIZON_SKY_RAIN_COLOR * luma(cloudColorAux) * 5.0,
        SkyRainStrength
    );

    darkCloudColor = mix(darkCloudColor, cloudColor, 0.22);
    darkCloudColor = mix(
        darkCloudColor,
        dayBlend(cloudColorAux, darkCloudColor, darkCloudColor),
        0.4
    );
}

vec3 applyVolumetricClouds(
    vec3 baseColor,
    vec3 rayDir,
    vec3 rayOrigin,
    float bright,
    float dither,
    vec3 cloudColor,
    vec3 darkCloudColor
) {
    if (rayDir.y <= 0.0) {
        return baseColor;
    }

    float viewYInv = 1.0 / rayDir.y;
    float planeDistanceInf = (CLOUD_PLANE - rayOrigin.y) * viewYInv;
    float planeDistanceSup = (CLOUD_PLANE_SUP - rayOrigin.y) * viewYInv;

    if (planeDistanceSup <= 0.0) {
        return baseColor;
    }

    planeDistanceInf = max(planeDistanceInf, 0.0);
    planeDistanceSup = max(planeDistanceSup, 0.0);

    vec3 intersectionPos = (rayDir * planeDistanceInf) + rayOrigin;
    vec3 intersectionPosSup = (rayDir * planeDistanceSup) + rayOrigin;

    float difSup = CLOUD_PLANE_SUP - CLOUD_PLANE_CENTER;
    float difInf = CLOUD_PLANE_CENTER - CLOUD_PLANE;
    vec3 increment = (intersectionPosSup - intersectionPos) / float(CLOUD_STEPS_AVG);
    float incrementDist = length(increment);

    float distAuxCoeff = (CLOUD_PLANE_SUP - CLOUD_PLANE) * 0.075;
    float distAuxCoeffBlur = distAuxCoeff * 0.3;
    float opacityDist = distAuxCoeff * 2.0 * viewYInv;
    float threshold = rainThreshold();

    float cloudValue = 0.0;
    float density = 0.0;
    bool firstContact = true;

    intersectionPos += increment * dither;

    for (int i = 0; i < CLOUD_STEPS_AVG; ++i) {
        vec2 sampleUv = fract(intersectionPos.xz * CLOUD_WORLD_SCALE + vec2(SkyCloudTime * CLOUD_HI_FACTOR));
        float currentValue = texture(Sampler4, sampleUv).r;
        currentValue = clamp((currentValue - threshold) / (1.0 - threshold), 0.0, 1.0);

        float surfaceInf = CLOUD_PLANE_CENTER - (currentValue * difInf);
        float surfaceSup = CLOUD_PLANE_CENTER + (currentValue * difSup);
        float cloudThickness = surfaceSup - surfaceInf;
        float currentOpacity = 0.0;

        if (intersectionPos.y > surfaceInf && intersectionPos.y < surfaceSup) {
            currentOpacity = min(incrementDist, cloudThickness);
        } else if (cloudThickness > 0.0 && i > 0) {
            float distanceAux = min(abs(intersectionPos.y - surfaceInf), abs(intersectionPos.y - surfaceSup));
            if (distanceAux < distAuxCoeffBlur) {
                float blurFactor = 1.0 - (distanceAux / distAuxCoeffBlur);
                currentOpacity = min(blurFactor * incrementDist, cloudThickness);
            }
        }

        if (currentOpacity > 0.0) {
            cloudValue += currentOpacity;
            if (firstContact) {
                firstContact = false;
                density = (surfaceSup - intersectionPos.y) / (CLOUD_PLANE_SUP - CLOUD_PLANE);
            }
        }

        intersectionPos += increment;
    }

    if (opacityDist <= 0.0) {
        return baseColor;
    }

    cloudValue = clamp(cloudValue / opacityDist, 0.0, 1.0);
    if (cloudValue <= 0.0) {
        return baseColor;
    }

    density = clamp(density, 0.0001, 1.0);

    float attFactor = mix(1.0, 0.75, bright * (1.0 - SkyRainStrength));
    cloudColor = mix(cloudColor * attFactor, darkCloudColor * attFactor, sqrt(density));

    float cloudValueApprox = sqrt(sqrt(cloudValue));
    cloudColor = mix(
        cloudColor,
        cloudColor * 13.0,
        (1.0 - cloudValueApprox) * bright * bright * (1.0 - SkyRainStrength)
    );

    return mix(baseColor, cloudColor, cloudValue * clamp((rayDir.y - 0.06) * 5.0, 0.0, 1.0));
}

vec3 sampleSkyReflection(vec3 rayDir, vec3 rayOrigin, float dither) {
    vec3 skyColor = buildSkyBase(rayDir);
    vec3 cloudColor;
    vec3 darkCloudColor;
    getCloudColors(cloudColor, darkCloudColor);

    float bright = dot(rayDir, normalize(SkySunDirection));
    bright = clamp(bright * bright * bright, 0.0, 1.0);
    return applyVolumetricClouds(skyColor, rayDir, rayOrigin, bright, dither, cloudColor, darkCloudColor);
}

vec2 sampleWaterNoise(vec2 uv) {
    return texture(Sampler3, fract(uv)).rg - 0.5;
}

vec2 sceneUvFromFragCoord() {
    vec2 sceneSize = vec2(textureSize(Sampler6, 0));
    return gl_FragCoord.xy / sceneSize;
}

vec3 waterRealLight(vec3 surfaceNormal, vec2 lightLevels) {
    vec2 illumination = makeup_adjust_light_levels(lightLevels);
    float visibleSky = illumination.y;
    float blockLight = illumination.x;
    float rawDirectLight = makeup_raw_direct_light(surfaceNormal);
    float directLightStrength = mix(0.0, clamp(rawDirectLight, 0.0, 1.0), visibleSky);
    vec3 directLightColor = makeup_direct_light_color();
    vec3 omniLight = makeup_omni_light(visibleSky, rawDirectLight, directLightColor);
    vec3 candleColor = makeup_candle_color(blockLight);
    return omniLight +
        (directLightColor * directLightStrength) * (1.0 - (rainStrength * 0.75)) +
        candleColor;
}

float waterThicknessApprox(float sceneDepth, float waterDepth) {
    float waterDistance = (2.0 * near * far) / (far + near - (2.0 * waterDepth - 1.0) * (far - near));
    float sceneDistance = (2.0 * near * far) / (far + near - (2.0 * sceneDepth - 1.0) * (far - near));
    float thickness = max(sceneDistance - waterDistance, 0.0);
    thickness *= thickness;
    return clamp((1.0 / -((thickness * WATER_ABSORPTION) + 1.125)) + 1.0, 0.0, 1.0);
}

vec3 applyWaterRefraction(vec3 waterColor, vec3 waterNormalBase, vec3 fragPos) {
    vec2 sceneUv = sceneUvFromFragCoord();
    vec2 refractedUv = sceneUv + waterNormalBase.xy * (0.075 / (1.0 + length(fragPos) * 0.4));
    refractedUv = clamp(refractedUv, vec2(0.001), vec2(0.999));

    float waterDepth = gl_FragCoord.z;
    float sceneDepth = texture(Sampler7, refractedUv).r;
    if (sceneDepth <= waterDepth + 0.00001) {
        refractedUv = sceneUv;
        sceneDepth = texture(Sampler7, sceneUv).r;
    }

    if (sceneDepth >= 0.9999) {
        return waterColor;
    }

    float thickness = isEyeInWater == 1 ? 0.0 : waterThicknessApprox(sceneDepth, waterDepth);
    vec3 sceneColor = texture(Sampler6, refractedUv).rgb;
    if (dot(sceneColor, sceneColor) <= 0.000001) {
        return waterColor;
    }

    return mix(sceneColor, waterColor, thickness);
}

vec3 cameraToScene(vec3 fragPos) {
    vec3 viewPos = mat3(ModelViewMat) * fragPos;
    vec4 clipPos = ProjMat * vec4(viewPos, 1.0);
    clipPos /= clipPos.w;
    return vec3(clipPos.x * 0.5 + 0.5, 0.5 - clipPos.y * 0.5, clipPos.z * 0.5 + 0.5);
}

vec4 waterReflectionCalc(vec3 hitPoint, vec3 reflected, float dither) {
    vec3 screenMarchPos = cameraToScene(hitPoint);
    vec3 currentMarchPoint = hitPoint;
    vec3 oldMarchPoint = hitPoint;
    vec3 pathIncrement = vec3(0.0);
    vec3 lastScreenMarchPos = screenMarchPos;
    float prevScreenDepth = screenMarchPos.z;
    float hitPointDepth = screenMarchPos.z;
    float screenDepth = 1.0;
    float depthDifference = 1.0;
    float infinite = 1.0;
    bool searchFlag = false;
    bool outOfEyeFlag = false;
    bool toFar = false;
    bool hiddenFlag = false;
    bool firstHidden = true;
    bool hiddens = false;
    int noHiddenSteps = 0;

    for (int i = 0; i < WATER_RAYMARCH_STEPS; ++i) {
        if (searchFlag) {
            pathIncrement *= 0.5;
            currentMarchPoint += pathIncrement * sign(depthDifference);
        } else {
            oldMarchPoint = currentMarchPoint;
            currentMarchPoint = hitPoint + ((reflected * exp2(float(i) + dither)) - reflected);
            pathIncrement = currentMarchPoint - oldMarchPoint;
        }

        lastScreenMarchPos = screenMarchPos;
        screenMarchPos = cameraToScene(currentMarchPoint);

        if (screenMarchPos.x < 0.0 || screenMarchPos.x > 1.0 ||
            screenMarchPos.y < 0.0 || screenMarchPos.y > 1.0 ||
            screenMarchPos.z < 0.0)
        {
            outOfEyeFlag = true;
        }

        if (screenMarchPos.z > 0.9999) {
            toFar = true;
        }

        vec2 sampleUv = clamp(screenMarchPos.xy, vec2(0.0), vec2(1.0));
        screenDepth = texture(Sampler7, sampleUv).r;
        depthDifference = screenDepth - screenMarchPos.z;

        if (depthDifference < 0.0 && abs(screenDepth - prevScreenDepth) > abs(screenMarchPos.z - lastScreenMarchPos.z)) {
            hiddenFlag = true;
            hiddens = true;
            if (firstHidden) {
                firstHidden = false;
            }
        } else if (depthDifference > 0.0) {
            hiddenFlag = false;
            if (!hiddens) {
                noHiddenSteps++;
            }
        }

        if (!searchFlag && depthDifference < 0.0 && !hiddenFlag) {
            searchFlag = true;
        }

        prevScreenDepth = screenDepth;
    }

    infinite = float(screenDepth > 0.9999);

    if (outOfEyeFlag) {
        infinite = 1.0;
    } else if (toFar) {
        if (screenDepth > 0.9999) {
            infinite = 1.0;
        } else if (!(noHiddenSteps < 3 || screenDepth > hitPointDepth)) {
            infinite = 1.0;
            screenMarchPos = vec3(1.0);
        }
    }

    float border = clamp((1.0 - (max(0.0, abs(screenMarchPos.y - 0.5)) * 2.0)) * 50.0, 0.0, 1.0);
    border = clamp(border - pow(screenMarchPos.y, 10.0), 0.0, 1.0);

    if (screenMarchPos.x > 1.0) {
        screenMarchPos.x = 1.0 - (screenMarchPos.x - 1.0);
    } else if (screenMarchPos.x < 0.0) {
        screenMarchPos.x = abs(screenMarchPos.x);
    }

    vec3 sceneReflection = texture(Sampler6, clamp(screenMarchPos.xy, vec2(0.001), vec2(0.999))).rgb;
    return vec4(sceneReflection, border * (1.0 - infinite));
}

vec3 sampleWaterReflection(vec3 fragPos, vec3 skyReflectionColor, vec3 reflected, float visibleSky, float dither) {
    vec4 reflection = waterReflectionCalc(fragPos, reflected, dither);
    return mix(skyReflectionColor * visibleSky, reflection.rgb, reflection.a);
}

vec3 normalWaves(vec3 worldPosition, float visibleSky) {
    vec3 pos = worldPosition.xzy;
    float speed = SkyCloudTime * 0.025;

    vec2 wave1 = sampleWaterNoise(((pos.xy - pos.z * 0.2) * 0.05) + vec2(speed, speed));
    vec2 wave2 = sampleWaterNoise(((pos.xy - pos.z * 0.2) * 0.03125) - vec2(speed));
    vec2 wave3 = sampleWaterNoise(((pos.xy - pos.z * 0.2) * 0.125) + vec2(speed, -speed)) * 0.66;

    vec2 partialWave = wave1 + wave2 + wave3;
    vec3 finalWave = vec3(partialWave, WATER_TURBULENCE - (SkyRainStrength * 0.6 * WATER_TURBULENCE * visibleSky));
    return normalize(finalWave);
}

vec3 applyWaveNormal(vec3 faceNormal, vec3 worldPosition) {
    if ((materialFlags & MATERIAL_WATER_SURFACE) == 0u) {
        return faceNormal;
    }

    float visibleSky = clamp(faceNormal.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 bump = normalWaves(worldPosition, visibleSky);

    vec3 tangent = abs(faceNormal.y) < 0.999 ? normalize(cross(WORLD_UP, faceNormal)) : vec3(1.0, 0.0, 0.0);
    vec3 bitangent = normalize(cross(faceNormal, tangent));
    return normalize(tangent * bump.x + bitangent * bump.y + faceNormal * bump.z);
}

void main() {
    vec4 atlasColor = texture(Sampler0, texCoord0);

    if ((materialFlags & MATERIAL_WATER) == 0u) {
        vec3 litColor;
        if (terrainLightingQuality >= 2) {
            litColor = atlasColor.rgb * vertexColor.rgb;
        } else if (terrainLightingQuality == 1) {
            litColor = atlasColor.rgb * rawVertexColor.rgb * lightColor.rgb;
        } else {
            vec3 worldNormal = makeup_terrain_normal(cameraRelativePosition);
            litColor = makeup_apply_lighting(atlasColor.rgb * rawVertexColor.rgb, worldNormal, rawLightLevels, cameraRelativePosition);
        }
        vec4 color = vec4(litColor, atlasColor.a * rawVertexColor.a);
        fragColor = apply_fog(
            color,
            sphericalVertexDistance,
            cylindricalVertexDistance,
            FogEnvironmentalStart,
            FogEnvironmentalEnd,
            FogRenderDistanceStart,
            FogRenderDistanceEnd,
            FogColor
        );
        return;
    }

    vec3 worldPosition = cameraRelativePosition + SkyCameraPosition;
    vec3 faceNormal = normalize(cross(dFdx(cameraRelativePosition), dFdy(cameraRelativePosition)));
    faceNormal = faceforward(faceNormal, cameraRelativePosition, faceNormal);
    vec3 surfaceNormal = applyWaveNormal(faceNormal, worldPosition);
    vec3 eyeVector = normalize(cameraRelativePosition);
    float fresnel = squarePow(clamp(1.0 + dot(surfaceNormal, eyeVector), 0.0, 1.0));
    float visibleSky = makeup_adjust_light_levels(rawLightLevels).y;

    float dither = interleavedGradientNoise(gl_FragCoord.xy);
    vec3 reflectionDir = normalize(reflect(eyeVector, surfaceNormal));
    vec3 skyReflectionColor = sampleSkyReflection(reflectionDir, worldPosition, dither);
    vec3 sunLightColor = dayBlend(LIGHT_SUNSET_COLOR, LIGHT_DAY_COLOR, LIGHT_NIGHT_COLOR);
    float sunSparkle = pow(max(dot(reflectionDir, normalize(SkySunDirection)), 0.0), 384.0) * (1.0 - SkyRainStrength);
    vec3 baseWaterColor = WATER_COLOR * waterRealLight(surfaceNormal, rawLightLevels);
    vec3 finalColor;

    if (waterQuality >= 3) {
        vec3 vanillaWaterColor = atlasColor.rgb * vertexColor.rgb;
        vec4 color = vec4(vanillaWaterColor, atlasColor.a * rawVertexColor.a);
        fragColor = apply_fog(
            color,
            sphericalVertexDistance,
            cylindricalVertexDistance,
            FogEnvironmentalStart,
            FogEnvironmentalEnd,
            FogRenderDistanceStart,
            FogRenderDistanceEnd,
            FogColor
        );
        return;
    } else if (waterQuality >= 2) {
        vec3 reflectionColor = skyReflectionColor + (sunLightColor * sunSparkle * 0.75);
        float reflectionStrength = clamp(fresnel * 0.18, 0.0, 0.18);
        finalColor = mix(baseWaterColor, reflectionColor, reflectionStrength);
    } else if (waterQuality == 1) {
        vec3 reflectionColor = skyReflectionColor + (sunLightColor * sunSparkle);
        float reflectionStrength = clamp(fresnel * (WATER_REFLEX_INDEX * 0.75), 0.0, 1.0);
        finalColor = mix(baseWaterColor, reflectionColor, reflectionStrength);
        finalColor = mix(finalColor, baseWaterColor, SkyRainStrength * 0.08);
    } else {
        vec3 reflectionColor = sampleWaterReflection(cameraRelativePosition, skyReflectionColor, reflectionDir, visibleSky, dither);
        reflectionColor += sunLightColor * sunSparkle * 1.35;

        vec3 waterNormalBase = (materialFlags & MATERIAL_WATER_SURFACE) != 0u ? normalWaves(worldPosition, visibleSky) : vec3(0.0, 0.0, 1.0);
        baseWaterColor = applyWaterRefraction(baseWaterColor, waterNormalBase, cameraRelativePosition);

        float reflectionStrength = clamp(fresnel * WATER_REFLEX_INDEX, 0.0, 1.0);
        finalColor = mix(baseWaterColor, reflectionColor, reflectionStrength);
        finalColor = mix(finalColor, baseWaterColor, SkyRainStrength * 0.08);
    }

    vec4 color = vec4(finalColor, 1.0);
    fragColor = apply_fog(
        color,
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );
}
