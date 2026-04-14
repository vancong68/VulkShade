#version 450

// MakeUpUltraFast sky rendering ported to Vulkan
// Original: Javier Garduno - GNU Lesser General Public License v3.0
// Color scheme: "New Shoka" (COLOR_SCHEME == 1)

layout(binding = 0) uniform SkyUBO {
    vec3 SkyUpDirection;
    vec3 SkyLeftDirection;
    vec3 SkyLookDirection;
    vec3 SkyCameraPosition;
    vec3 SkySunDirection;
    vec3 SkySunRightDirection;
    vec3 SkySunUpDirection;
    vec3 SkyMoonDirection;
    vec3 SkyMoonRightDirection;
    vec3 SkyMoonUpDirection;
    vec2 ScreenSize;
    float SkyCloudTime;
    float SkyDayMoment;
    float SkyDayMixer;
    float SkyNightMixer;
    float SkyRainStrength;
    int SkyMoonPhase;
    float SkyStarBrightness;
    int skyQuality;
};

layout(binding = 1) uniform sampler2D Sampler0;
layout(binding = 2) uniform sampler2D Sampler1;
layout(binding = 3) uniform sampler2D Sampler2;

layout(location = 0) in vec2 inUV;
layout(location = 0) out vec4 fragColor;

// --- Constants ---

#define NIGHT_BRIGHT 0.60
#define NIGHT_BRIGHT_PHASE (NIGHT_BRIGHT + (NIGHT_BRIGHT * (abs(4.0 - float(SkyMoonPhase)) * 0.25)))

// New Shoka color scheme
#define ZENITH_SUNSET_COLOR vec3(0.143, 0.24394118, 0.36450981)
#define ZENITH_DAY_COLOR vec3(0.143, 0.24394118, 0.36450981)
#define ZENITH_NIGHT_COLOR (vec3(0.014, 0.019, 0.025) * NIGHT_BRIGHT_PHASE)

#define HORIZON_SUNSET_COLOR vec3(1.0, 0.648, 0.37824)
#define HORIZON_DAY_COLOR vec3(0.65, 0.91, 1.3)
#define HORIZON_NIGHT_COLOR (vec3(0.0213, 0.0306, 0.0387) * NIGHT_BRIGHT_PHASE)

#define ZENITH_SKY_RAIN_COLOR vec3(0.7, 0.85, 1.0)
#define HORIZON_SKY_RAIN_COLOR vec3(0.35, 0.425, 0.5)

#define LIGHT_SUNSET_COLOR vec3(1.0, 0.59, 0.35)
#define LIGHT_DAY_COLOR vec3(0.90, 0.84, 0.79)
#define LIGHT_NIGHT_COLOR vec3(0.05, 0.05, 0.06)

#define CLOUD_PLANE_SUP 590.0
#define CLOUD_PLANE_CENTER 375.0
#define CLOUD_PLANE 319.0
#define CLOUD_STEPS_AVG 10
#define CLOUD_WORLD_SCALE 0.0002777777777777778
#define CLOUD_HI_FACTOR 0.0016666666666666666
#define SUN_QUAD_DISTANCE 100.0
#define SUN_HALF_SIZE 30.0
#define MOON_QUAD_DISTANCE 100.0
#define MOON_HALF_SIZE 20.0

// --- Color conversion (RGB <-> XYZ for perceptual blending) ---

vec3 rgbToXyz(vec3 rgb) {
    vec3 rgb2 = rgb;
    vec3 mask = vec3(greaterThan(rgb, vec3(0.04045)));
    rgb2 = mix(rgb2 / 12.92, pow((rgb2 + 0.055) / 1.055, vec3(2.4)), mask);

    const mat3 rgbToXyzMatrix = mat3(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041
    );

    return rgbToXyzMatrix * rgb2;
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

// --- Luma ---

float luma(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

// --- Day blend functions (from MakeUpUltraFast) ---

vec3 dayBlend(vec3 sunset, vec3 day, vec3 night) {
    vec3 dayColor = mix(sunset, day, SkyDayMixer);
    vec3 nightColor = mix(sunset, night, SkyNightMixer);
    return mix(dayColor, nightColor, step(0.5, SkyDayMoment));
}

// --- Dither (simple interleaved gradient noise) ---

float interleavedGradientNoise(vec2 coord) {
    return fract(52.9829189 * fract(0.06711056 * coord.x + 0.00583715 * coord.y));
}

// --- Star field ---

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float starField(vec3 dir) {
    // Project direction onto a grid
    vec3 absDir = abs(dir);
    vec2 uv2;
    if (absDir.x >= absDir.y && absDir.x >= absDir.z) {
        uv2 = dir.yz / dir.x;
    } else if (absDir.y >= absDir.x && absDir.y >= absDir.z) {
        uv2 = dir.xz / dir.y;
    } else {
        uv2 = dir.xy / dir.z;
    }

    // Grid cells for stars
    vec2 gridUV = uv2 * 200.0;
    vec2 gridID = floor(gridUV);
    vec2 gridFract = fract(gridUV) - 0.5;

    float star = 0.0;
    float h = hash21(gridID);

    // Only some cells have stars
    if (h > 0.92) {
        // Random offset within cell
        vec2 offset = vec2(hash21(gridID + 1.0), hash21(gridID + 2.0)) - 0.5;
        float d = length(gridFract - offset * 0.6);

        // Star brightness varies
        float brightness = (h - 0.92) / 0.08;
        star = smoothstep(0.05, 0.0, d) * brightness;

        // Twinkle
        float twinkle = sin(SkyDayMoment * 100.0 + h * 6.28318) * 0.3 + 0.7;
        star *= twinkle;
    }

    return star;
}

float rainThreshold() {
    float invRain = clamp(1.0 - SkyRainStrength, 0.0, 1.0);
    float smoothedInvRain = invRain * invRain * (3.0 - 2.0 * invRain);
    return smoothedInvRain * 0.3 + 0.25;
}

float skyLumaCorrection() {
    float sunLuma = luma(dayBlend(LIGHT_SUNSET_COLOR, LIGHT_DAY_COLOR, LIGHT_NIGHT_COLOR));
    return 1.5 / ((sunLuma * -2.5) + 3.5);
}

float sunVisibilityFactor(vec3 sunDir) {
    return smoothstep(-0.12, 0.02, sunDir.y) * (1.0 - SkyRainStrength * 0.85);
}

float moonVisibilityFactor(vec3 moonDir) {
    return smoothstep(-0.12, 0.02, moonDir.y) * (1.0 - SkyRainStrength * 0.85);
}

vec3 sunLightColor() {
    vec3 directLightColor = dayBlend(LIGHT_SUNSET_COLOR, LIGHT_DAY_COLOR, LIGHT_NIGHT_COLOR);
    return mix(directLightColor, ZENITH_SKY_RAIN_COLOR * luma(directLightColor), SkyRainStrength);
}

vec3 moonLightColor() {
    vec3 baseMoonColor = mix(
        LIGHT_NIGHT_COLOR * vec3(2.4, 2.45, 2.8),
        mix(HORIZON_NIGHT_COLOR, ZENITH_NIGHT_COLOR, 0.5) * vec3(1.9, 2.0, 2.25),
        0.35
    );
    return mix(baseMoonColor, ZENITH_SKY_RAIN_COLOR * luma(baseMoonColor), SkyRainStrength * 0.6);
}

float sunDayNightMix() {
    float worldTime = fract(SkyDayMoment) * 24000.0;
    float lightMixA = ((worldTime >= 0.0 && worldTime < 12485.0) || worldTime >= 23515.0) ? 1.0 : 0.0;
    float lightMixB = (worldTime >= 12485.0 && worldTime < 13085.0)
        ? 1.0 - ((worldTime - 12485.0) * 0.0016666666666666668)
        : 0.0;
    float lightMixD = (worldTime >= 22915.0 && worldTime < 23515.0)
        ? (worldTime - 22915.0) * 0.0016666666666666668
        : 0.0;
    return max(lightMixA, max(lightMixB, lightMixD));
}

float sunVolumetricDayMixer() {
    float momentAux5 = (SkyDayMoment * 4.0) - 1.0;
    float momentAux6 = momentAux5 * momentAux5;
    momentAux6 *= momentAux6;
    float dayVolMixer = clamp(((-momentAux6 + 1.0) * 7.0) + 1.0, 1.0, 8.0);

    float momentAux7 = (SkyDayMoment * 4.0) - 3.0;
    float momentAux8 = momentAux7 * momentAux7;
    momentAux8 *= momentAux8;
    float nightVolMixer = clamp(((-momentAux8 + 1.0) * 7.0) + 1.0, 1.0, 8.0);

    return max(dayVolMixer, nightVolMixer);
}

vec3 applyCelestialRadianceLobe(
    vec3 baseColor,
    vec3 eyeDirection,
    vec3 celestialDir,
    float celestialVisibility,
    float phaseWeight,
    float exponent,
    float strength,
    vec3 radianceColor
) {
    vec3 centerEyeDirection = normalize(SkyLookDirection);
    float celestialFacing = clamp(dot(eyeDirection, celestialDir), 0.0, 1.0);
    float centerFacing = clamp(dot(centerEyeDirection, celestialDir), 0.0, 1.0);
    if (celestialVisibility <= 0.0 || phaseWeight <= 0.0) {
        return baseColor;
    }

    float volumetricIntensity = clamp(centerFacing * celestialFacing, 0.0, 1.0);
    volumetricIntensity = pow(volumetricIntensity, exponent) * strength * phaseWeight;

    float mixFactor = clamp(volumetricIntensity * celestialVisibility * (1.0 - SkyRainStrength) * 1.25, 0.0, 1.0);
    return mix(baseColor, radianceColor, mixFactor);
}

vec3 applyCelestialRadiance(vec3 baseColor, vec3 eyeDirection) {
    vec3 sunDir = normalize(SkySunDirection);
    vec3 moonDir = normalize(SkyMoonDirection);
    float dayMix = sunDayNightMix();
    float nightMix = 1.0 - dayMix;
    float sunExponent = sunVolumetricDayMixer();
    float moonExponent = max(2.2, sunVolumetricDayMixer() * 0.35);

    baseColor = applyCelestialRadianceLobe(
        baseColor,
        eyeDirection,
        sunDir,
        sunVisibilityFactor(sunDir),
        dayMix,
        sunExponent,
        0.5,
        sunLightColor() * 1.2
    );

    baseColor = applyCelestialRadianceLobe(
        baseColor,
        eyeDirection,
        moonDir,
        moonVisibilityFactor(moonDir),
        nightMix,
        moonExponent,
        0.8,
        moonLightColor()
    );

    return baseColor;
}

vec3 sampleSunDisk(vec3 eyeDirection) {
    vec3 sunDir = normalize(SkySunDirection);
    vec3 sunRight = normalize(SkySunRightDirection);
    vec3 sunUp = normalize(SkySunUpDirection);
    float sunFacing = dot(eyeDirection, sunDir);
    if (sunFacing <= 0.0) {
        return vec3(0.0);
    }

    vec2 planeCoords = vec2(dot(eyeDirection, sunRight), dot(eyeDirection, sunUp)) / max(sunFacing, 1e-4);
    vec2 sunUv = planeCoords * (0.5 * SUN_QUAD_DISTANCE / SUN_HALF_SIZE) + 0.5;

    float sunVisibility = sunVisibilityFactor(sunDir);
    if (any(lessThan(sunUv, vec2(0.0))) || any(greaterThan(sunUv, vec2(1.0)))) {
        return vec3(0.0);
    }

    vec3 sunSample = texture(Sampler1, sunUv).rgb;
    return sunSample * skyLumaCorrection() * sunVisibility;
}

vec3 sampleMoonDisk(vec3 eyeDirection) {
    vec3 moonDir = normalize(SkyMoonDirection);
    vec3 moonRight = normalize(SkyMoonRightDirection);
    vec3 moonUp = normalize(SkyMoonUpDirection);
    float moonFacing = dot(eyeDirection, moonDir);
    if (moonFacing <= 0.0) {
        return vec3(0.0);
    }

    vec2 planeCoords = vec2(dot(eyeDirection, moonRight), dot(eyeDirection, moonUp)) / max(moonFacing, 1e-4);
    vec2 moonUv = planeCoords * (0.5 * MOON_QUAD_DISTANCE / MOON_HALF_SIZE) + 0.5;
    if (any(lessThan(moonUv, vec2(0.0))) || any(greaterThan(moonUv, vec2(1.0)))) {
        return vec3(0.0);
    }

    // Vanilla's moon atlas is a 4x2 phase sheet with the quad's U orientation flipped.
    int phase = SkyMoonPhase & 7;
    int phaseX = phase % 4;
    int phaseY = phase / 4;
    vec2 phaseMin = vec2(float(phaseX) * 0.25, float(phaseY) * 0.5);
    vec2 phaseMax = phaseMin + vec2(0.25, 0.5);
    vec2 atlasUv = mix(phaseMin, phaseMax, vec2(1.0 - moonUv.x, moonUv.y));

    float moonVisibility = smoothstep(-0.12, 0.02, moonDir.y) * (1.0 - SkyRainStrength * 0.85);
    vec3 moonSample = texture(Sampler2, atlasUv).rgb;
    return moonSample * skyLumaCorrection() * moonVisibility;
}

float celestialCloudBright(vec3 eyeDirection) {
    vec3 sunDir = normalize(SkySunDirection);
    vec3 moonDir = normalize(SkyMoonDirection);
    float dayMix = sunDayNightMix();
    float nightMix = 1.0 - dayMix;
    float sunBright = dayMix * clamp(dot(eyeDirection, sunDir), 0.0, 1.0);
    float moonBright = nightMix * clamp(dot(eyeDirection, moonDir), 0.0, 1.0);
    float bright = max(sunBright, moonBright);
    return clamp(bright * bright * bright, 0.0, 1.0);
}

vec3 applyVolumetricClouds(
    vec3 baseColor,
    vec3 eyeDirection,
    float bright,
    float dither,
    vec3 cloudColor,
    vec3 darkCloudColor
) {
    if (eyeDirection.y <= 0.0) {
        return baseColor;
    }

    float viewYInv = 1.0 / eyeDirection.y;
    float planeDistanceInf = (CLOUD_PLANE - SkyCameraPosition.y) * viewYInv;
    float planeDistanceSup = (CLOUD_PLANE_SUP - SkyCameraPosition.y) * viewYInv;

    if (planeDistanceSup <= 0.0) {
        return baseColor;
    }

    planeDistanceInf = max(planeDistanceInf, 0.0);
    planeDistanceSup = max(planeDistanceSup, 0.0);

    vec3 intersectionPos = (eyeDirection * planeDistanceInf) + SkyCameraPosition;
    vec3 intersectionPosSup = (eyeDirection * planeDistanceSup) + SkyCameraPosition;

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
        float currentValue = texture(Sampler0, sampleUv).r;
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

    float cloudOcclusion = cloudValue * clamp((eyeDirection.y - 0.06) * 5.0, 0.0, 1.0);
    return mix(baseColor, cloudColor, cloudOcclusion);
}

// --- Main ---

void main() {
    // Reconstruct the world ray from Minecraft's near-plane basis vectors.
    // Use gl_FragCoord instead of fullscreen-triangle UVs so viewport Y-flip does not
    // change the ray mapping.
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec2 ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    vec3 worldDir = normalize(SkyLookDirection + ndc.y * SkyUpDirection - ndc.x * SkyLeftDirection);

    // World up is always (0, 1, 0)
    const vec3 worldUp = vec3(0.0, 1.0, 0.0);

    // Compute up-direction dot product for sky gradient
    float cloudDither = interleavedGradientNoise(gl_FragCoord.xy);
    float gradientDither = (cloudDither - 0.5) * 0.03125;
    float n_u = clamp(dot(worldDir, worldUp) + gradientDither, 0.0, 1.0);

    // Compute zenith sky color (from MakeUpUltraFast hi_sky.glsl)
    vec3 zenithSkyColorRGB = dayBlend(
        ZENITH_SUNSET_COLOR,
        ZENITH_DAY_COLOR,
        ZENITH_NIGHT_COLOR
    );
    zenithSkyColorRGB = mix(
        zenithSkyColorRGB,
        ZENITH_SKY_RAIN_COLOR * luma(zenithSkyColorRGB),
        SkyRainStrength
    );
    vec3 zenithSkyColor = rgbToXyz(zenithSkyColorRGB);

    // Compute horizon sky color (from MakeUpUltraFast low_sky.glsl)
    vec3 horizonSkyColorRGB = dayBlend(
        HORIZON_SUNSET_COLOR,
        HORIZON_DAY_COLOR,
        HORIZON_NIGHT_COLOR
    );
    horizonSkyColorRGB = mix(
        horizonSkyColorRGB,
        HORIZON_SKY_RAIN_COLOR * luma(horizonSkyColorRGB),
        SkyRainStrength
    );
    vec3 horizonSkyColor = rgbToXyz(horizonSkyColorRGB);

    // Mix between horizon and zenith based on view angle.
    vec3 skyColorXYZ = mix(horizonSkyColor, zenithSkyColor, smoothstep(0.0, 1.0, pow(n_u, 0.333)));
    vec3 skyColor = xyzToRgb(skyColorXYZ);

    // Darken the sky below the horizon.
    float verticalDot = dot(worldDir, worldUp);
    float belowHorizon = clamp(-verticalDot * 3.0, 0.0, 1.0);
    skyColor = mix(skyColor, skyColor * 0.15, belowHorizon);

    // Stars are only visible at night and above the horizon.
    float aboveHorizon = smoothstep(-0.02, 0.05, verticalDot);
    if (skyQuality >= 2) {
        float stars = starField(worldDir) * SkyStarBrightness;
        skyColor += vec3(stars) * aboveHorizon;
    }
    skyColor = applyCelestialRadiance(skyColor, worldDir);
    skyColor += sampleSunDisk(worldDir);
    skyColor += sampleMoonDisk(worldDir);

    if (skyQuality < 3) {
        fragColor = vec4(skyColor, 1.0);
        return;
    }

    // MakeUpUltraFast volumetric cloud colors.
    vec3 darkCloudColor = dayBlend(
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

    vec3 cloudColor = mix(
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

    float bright = celestialCloudBright(worldDir);
    skyColor = applyVolumetricClouds(skyColor, worldDir, bright, cloudDither, cloudColor, darkCloudColor);

    fragColor = vec4(skyColor, 1.0);
}
