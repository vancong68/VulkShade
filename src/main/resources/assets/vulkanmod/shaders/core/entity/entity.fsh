#version 330

#include "fog.glsl"

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};

layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

layout(std140) uniform ShadowData {
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
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;

#include "makeup_lighting.glsl"

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 cameraRelativePosition;
in vec3 rawNormal;
in vec2 rawLightLevels;
in vec4 rawTintColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

#ifdef PER_FACE_LIGHTING
    vec4 shadedColor = color * (gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack) * ColorModulator;
#else
    vec4 shadedColor = color * vertexColor * ColorModulator;
#endif

#ifndef NO_OVERLAY
    shadedColor.rgb = mix(overlayColor.rgb, shadedColor.rgb, overlayColor.a);
#endif

    shadedColor.rgb += texture(Sampler6, texCoord0).rgb * rawTintColor.rgb;

#ifndef EMISSIVE
    vec4 vanillaLitColor = shadedColor * lightMapColor;
    vec3 worldNormal = makeup_oriented_normal(rawNormal);
    vec3 litColor = makeup_apply_entity_lighting(
        vanillaLitColor.rgb,
        shadedColor.rgb,
        worldNormal,
        rawLightLevels,
        cameraRelativePosition
    );
    shadedColor = vec4(litColor, shadedColor.a);
#endif

    fragColor = apply_fog(
        shadedColor,
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );
}
