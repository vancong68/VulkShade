const float MAKEUP_SHADOW_DIST = 0.83;
const float MAKEUP_SHADOW_BLUR = 2.0;
const float MAKEUP_SHADOW_FIX_FACTOR = 0.07;
const float MAKEUP_NIGHT_BRIGHT = 0.60;
const float MAKEUP_OMNI_TINT = 0.25;
const float MAKEUP_AVOID_DARK_LEVEL = 0.03;

const vec3 MAKEUP_ZENITH_SKY_RAIN_COLOR = vec3(0.7, 0.85, 1.0);
const vec3 MAKEUP_LIGHT_SUNSET_COLOR = vec3(1.0, 0.588, 0.3555);
const vec3 MAKEUP_LIGHT_DAY_COLOR = vec3(0.90, 0.84, 0.79);
const vec3 MAKEUP_ZENITH_SUNSET_COLOR = vec3(0.143, 0.24394118, 0.36450981);
const vec3 MAKEUP_ZENITH_DAY_COLOR = vec3(0.143, 0.24394118, 0.36450981);
const vec3 MAKEUP_CANDLE_BASELIGHT = vec3(0.27475, 0.17392353, 0.0899);

#ifndef MAKEUP_TERRAIN_SHADOW_QUALITY
#define MAKEUP_TERRAIN_SHADOW_QUALITY 0
#endif

float makeup_luma(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

float makeup_color_average(vec3 color) {
    return (color.r + color.g + color.b) * 0.33333334;
}

float makeup_sixth_pow(float value) {
    float value2 = value * value;
    return value2 * value2 * value2;
}

float makeup_night_bright_phase() {
    return MAKEUP_NIGHT_BRIGHT + (MAKEUP_NIGHT_BRIGHT * (abs(4.0 - float(moonPhase)) * 0.25));
}

vec3 makeup_light_night_color() {
    return vec3(0.04786874, 0.05175001, 0.06112969) * makeup_night_bright_phase();
}

vec3 makeup_zenith_night_color() {
    return vec3(0.014, 0.019, 0.025) * makeup_night_bright_phase();
}

vec3 makeup_day_blend(vec3 sunset, vec3 day, vec3 night) {
    vec3 dayColor = mix(sunset, day, dayMixer);
    vec3 nightColor = mix(sunset, night, nightMixer);
    return mix(dayColor, nightColor, step(0.5, dayMoment));
}

float makeup_interleaved_gradient_noise(vec2 screenPos) {
    return fract(52.9829189 * fract(dot(screenPos, vec2(0.06711056, 0.00583715))));
}

vec3 makeup_shadow_pos(vec3 worldPos) {
    vec4 shadowPos = shadowProjection * shadowModelView * vec4(worldPos, 1.0);
    float distortion = length(shadowPos.xy) * MAKEUP_SHADOW_DIST + (1.0 - MAKEUP_SHADOW_DIST);
    shadowPos.xy /= distortion;
    shadowPos.z *= 0.2;
    return vec3(shadowPos.x * 0.5 + 0.5, 0.5 - shadowPos.y * 0.5, shadowPos.z);
}

float makeup_sample_shadow_compare(vec3 shadowPos, vec2 offset, float zBias) {
    vec2 uv = shadowPos.xy + offset;
    if (uv.x <= 0.0 || uv.x >= 1.0 || uv.y <= 0.0 || uv.y >= 1.0 || shadowPos.z <= 0.0 || shadowPos.z >= 1.0) {
        return 1.0;
    }

    float shadowDepth = texture(Sampler5, uv).r;
    if (shadowDepth >= 0.999999) {
        return 1.0;
    }

    return shadowPos.z - zBias <= shadowDepth ? 1.0 : 0.0;
}

// MakeUp-style 4-tap soft shadow with two perpendicular dither-rotated offsets.
float makeup_shadow(vec3 shadowPos, float zBias) {
    float shadowMapRes = float(textureSize(Sampler5, 0).x);
    float dither = makeup_interleaved_gradient_noise(gl_FragCoord.xy);

    float currentRadius = dither;
    float angle = dither * 6.283185307179586;
    float angle2 = angle + 1.5707963267948966;

    vec2 offset = (vec2(cos(angle), sin(angle)) * currentRadius * MAKEUP_SHADOW_BLUR) / shadowMapRes;
    vec2 offset2 = (vec2(cos(angle2), sin(angle2)) * (1.0 - currentRadius) * MAKEUP_SHADOW_BLUR) / shadowMapRes;

    float ditherZBias = angle * 0.00002;
    float totalBias = zBias + ditherZBias;

    float shadow = 0.0;
    shadow += makeup_sample_shadow_compare(shadowPos, offset, totalBias);
    shadow += makeup_sample_shadow_compare(shadowPos, -offset, totalBias);
    shadow += makeup_sample_shadow_compare(shadowPos, offset2, totalBias);
    shadow += makeup_sample_shadow_compare(shadowPos, -offset2, totalBias);

    return shadow * 0.25;
}

float makeup_shadow_pcf5x5(vec3 shadowPos, float zBias) {
    float shadowMapRes = float(textureSize(Sampler5, 0).x);
    float pixelSize = 1.0 / shadowMapRes;
    float totalBias = zBias + 0.00005;
    float shadow = 0.0;
    for (int x = -2; x <= 2; ++x) {
        for (int y = -2; y <= 2; ++y) {
            vec2 offset = vec2(float(x), float(y)) * pixelSize * 1.5;
            shadow += makeup_sample_shadow_compare(shadowPos, offset, totalBias);
        }
    }
    return shadow / 25.0;
}

float makeup_shadow_fast(vec3 shadowPos, float zBias) {
    return makeup_sample_shadow_compare(shadowPos, vec2(0.0), zBias);
}

// Compute shadow value with MakeUp-style normal-based bias offset.
// Normal is oriented toward the light for stable derivative normals.
float makeup_shadow_value(vec3 worldPos, vec3 worldNormal) {
    // Orient normal toward light (handles derivative normal sign instability)
    vec3 lightDir = normalize(shadowLightPosition);
    float NdotL = dot(worldNormal, lightDir);
    vec3 shadowNormal = NdotL >= 0.0 ? worldNormal : -worldNormal;
    NdotL = clamp(abs(NdotL), 0.0, 1.0);

    // MakeUp normal-based bias: push shadow sample along normal before transform.
    // bias = normal * min(SHADOW_FIX_FACTOR + distance * 0.005, 0.5) * (2.0 - NdotL)
    float distanceFactor = length(worldPos) * 0.005;
    float biasMagnitude = min(MAKEUP_SHADOW_FIX_FACTOR + distanceFactor, 0.5) * (2.0 - NdotL);
    vec3 biasedPos = worldPos + shadowNormal * biasMagnitude;

    vec3 shadowPos = makeup_shadow_pos(biasedPos);
    if (shadowPos.x <= 0.0 || shadowPos.x >= 1.0 || shadowPos.y <= 0.0 || shadowPos.y >= 1.0 || shadowPos.z <= 0.0 || shadowPos.z >= 1.0) {
        return 1.0;
    }

    float centerDepth = texture(Sampler5, shadowPos.xy).r;
    if (centerDepth >= 0.999999) {
        return 1.0;
    }

    // Edge fade: pow(edge, 10) via multiplication chain (matches MakeUp vertex shader)
    float edge = length(shadowPos.xy * 2.0 - 1.0);
    float edge2 = edge * edge;
    float edge4 = edge2 * edge2;
    float edge8 = edge4 * edge4;
    float shadowDiffuse = clamp(edge8 * edge2, 0.0, 1.0);

    float shadow = makeup_shadow_pcf5x5(shadowPos, 0.0);
    return mix(shadow, 1.0, shadowDiffuse);
}

float makeup_terrain_shadow_value(vec3 worldPos, vec3 worldNormal) {
    if (MAKEUP_TERRAIN_SHADOW_QUALITY >= 2) {
        return 1.0;
    }

    vec3 lightDir = normalize(shadowLightPosition);
    float NdotL = dot(worldNormal, lightDir);
    vec3 shadowNormal = NdotL >= 0.0 ? worldNormal : -worldNormal;
    NdotL = clamp(abs(NdotL), 0.0, 1.0);

    float distanceFactor = length(worldPos) * 0.005;
    float biasMagnitude = min(MAKEUP_SHADOW_FIX_FACTOR + distanceFactor, 0.5) * (2.0 - NdotL);
    vec3 biasedPos = worldPos + shadowNormal * biasMagnitude;

    vec3 shadowPos = makeup_shadow_pos(biasedPos);
    if (shadowPos.x <= 0.0 || shadowPos.x >= 1.0 || shadowPos.y <= 0.0 || shadowPos.y >= 1.0 || shadowPos.z <= 0.0 || shadowPos.z >= 1.0) {
        return 1.0;
    }

    float centerDepth = texture(Sampler5, shadowPos.xy).r;
    if (centerDepth >= 0.999999) {
        return 1.0;
    }

    float edge = length(shadowPos.xy * 2.0 - 1.0);
    float edge2 = edge * edge;
    float edge4 = edge2 * edge2;
    float edge8 = edge4 * edge4;
    float shadowDiffuse = clamp(edge8 * edge2, 0.0, 1.0);

    float shadow = MAKEUP_TERRAIN_SHADOW_QUALITY == 1
        ? makeup_shadow_fast(shadowPos, 0.0)
        : makeup_shadow_pcf5x5(shadowPos, 0.0);
    return mix(shadow, 1.0, shadowDiffuse);
}

vec2 makeup_adjust_light_levels(vec2 rawLightLevels) {
    vec2 illumination = clamp(rawLightLevels, 0.0, 1.0);
    illumination.y = max(illumination.y - 0.065, 0.0) * 1.06951871657754;
    return clamp(illumination, 0.0, 1.0);
}

float makeup_raw_direct_light(vec3 normal) {
    vec3 astroVector = normalize(sunPosition);
    float astroLightStrength = dot(normalize(normal), astroVector);
    return mix(-astroLightStrength, astroLightStrength, dayNightMix);
}

vec3 makeup_direct_light_color() {
    vec3 directLightColor = makeup_day_blend(
        MAKEUP_LIGHT_SUNSET_COLOR,
        MAKEUP_LIGHT_DAY_COLOR,
        makeup_light_night_color()
    );
    return mix(directLightColor, MAKEUP_ZENITH_SKY_RAIN_COLOR * makeup_luma(directLightColor) * 0.4, rainStrength);
}

vec3 makeup_zenith_light_color() {
    return makeup_day_blend(
        MAKEUP_ZENITH_SUNSET_COLOR,
        MAKEUP_ZENITH_DAY_COLOR,
        makeup_zenith_night_color()
    );
}

vec3 makeup_candle_color(float blockLight) {
    vec3 candleColor = MAKEUP_CANDLE_BASELIGHT * (blockLight * sqrt(blockLight) + makeup_sixth_pow(blockLight * 1.17));
    return clamp(candleColor, vec3(0.0), vec3(4.0));
}

vec3 makeup_omni_light(float visibleSky, float rawDirectLight, vec3 directLightColor) {
    float omniStrength = ((rawDirectLight + 1.0) * 0.25) + 0.75;

    vec3 zenithColor = makeup_zenith_light_color();
    vec3 omniColor = mix(zenithColor, directLightColor * 0.45, MAKEUP_OMNI_TINT);
    float omniColorLuma = makeup_color_average(omniColor);
    vec3 omniColorMin = omniColor * (MAKEUP_AVOID_DARK_LEVEL / max(omniColorLuma, 0.0001));
    omniColor = max(omniColor, omniColorMin);

    return mix(omniColorMin, omniColor, visibleSky) * omniStrength;
}

vec3 makeup_apply_lighting(vec3 baseColor, vec3 normal, vec2 rawLightLevels, vec3 worldPos) {
    vec2 illumination = makeup_adjust_light_levels(rawLightLevels);
    float visibleSky = illumination.y;
    float blockLight = illumination.x;

    float rawDirectLight = makeup_raw_direct_light(normal);
    float directLightStrength = mix(0.0, clamp(rawDirectLight, 0.0, 1.0), visibleSky);
    vec3 directLightColor = makeup_direct_light_color();
    vec3 omniLight = makeup_omni_light(visibleSky, rawDirectLight, directLightColor);
    vec3 candleColor = makeup_candle_color(blockLight);
    float shadowValue = makeup_terrain_shadow_value(worldPos, normal);

    vec3 realLight = omniLight +
        (shadowValue * directLightColor * directLightStrength) * (1.0 - (rainStrength * 0.75)) +
        candleColor;

    return baseColor * realLight;
}

float makeup_terrain_sun_facing(vec3 worldNormal) {
    vec3 lightDir = normalize(shadowLightPosition);
    return clamp(abs(dot(normalize(worldNormal), lightDir)), 0.0, 1.0);
}

vec3 makeup_apply_terrain_lighting(vec3 vanillaLitColor, vec3 baseColor, vec3 worldNormal, vec2 rawLightLevels, vec3 worldPos) {
    vec2 illumination = makeup_adjust_light_levels(rawLightLevels);
    float visibleSky = illumination.y;
    float skySunlight = visibleSky * dayNightMix * (1.0 - (rainStrength * 0.75));
    float sunFacing = makeup_terrain_sun_facing(worldNormal);
    float sunStrength = skySunlight * mix(0.35, 1.0, sunFacing);

    // Keep vanilla terrain lighting as the base in this renderer, then layer
    // MakeUp-style sun/shadow on top. Replacing the full base lightmap path
    // made outdoor terrain read globally dark here.
    float shadowValue = makeup_terrain_shadow_value(worldPos, worldNormal);
    float shadowDarkness = mix(0.58, 1.0, shadowValue);
    vec3 directLightColor = makeup_direct_light_color();
    vec3 shadowedBase = vanillaLitColor * mix(1.0, shadowDarkness, sunStrength);
    vec3 sunlightBoost = baseColor * directLightColor * (shadowValue * sunStrength * 0.35);

    return shadowedBase + sunlightBoost;
}

vec3 makeup_apply_fast_terrain_lighting(vec3 vanillaLitColor, vec3 baseColor, vec2 rawLightLevels) {
    vec2 illumination = makeup_adjust_light_levels(rawLightLevels);
    float visibleSky = illumination.y;
    float skySunlight = visibleSky * dayNightMix * (1.0 - (rainStrength * 0.75));
    vec3 directLightColor = makeup_direct_light_color();
    vec3 sunlightBoost = baseColor * directLightColor * (skySunlight * 0.18);
    return vanillaLitColor + sunlightBoost;
}

vec3 makeup_apply_entity_lighting(vec3 vanillaLitColor, vec3 baseColor, vec3 worldNormal, vec2 rawLightLevels, vec3 worldPos) {
    vec2 illumination = makeup_adjust_light_levels(rawLightLevels);
    float visibleSky = illumination.y;
    float rawDirectLight = makeup_raw_direct_light(worldNormal);
    float sunStrength = clamp(rawDirectLight, 0.0, 1.0) * visibleSky * (1.0 - (rainStrength * 0.75));

    float shadowValue = makeup_shadow_value(worldPos, worldNormal);
    float shadowDarkness = mix(0.58, 1.0, shadowValue);
    vec3 directLightColor = makeup_direct_light_color();
    vec3 shadowedBase = vanillaLitColor * mix(1.0, shadowDarkness, sunStrength);
    vec3 sunlightBoost = baseColor * directLightColor * (shadowValue * sunStrength * 0.35);

    return shadowedBase + sunlightBoost;
}

vec3 makeup_terrain_normal(vec3 worldPos) {
    vec3 normal = normalize(cross(dFdx(worldPos), dFdy(worldPos)));

    // Terrain lighting only needs the visible-facing normal. Using the camera
    // vector is more stable here than gl_FrontFacing under the Vulkan path.
    return faceforward(normal, worldPos, normal);
}

vec3 makeup_oriented_normal(vec3 normal) {
    vec3 orientedNormal = normalize(normal);
    if (!gl_FrontFacing) {
        orientedNormal = -orientedNormal;
    }
    return orientedNormal;
}
