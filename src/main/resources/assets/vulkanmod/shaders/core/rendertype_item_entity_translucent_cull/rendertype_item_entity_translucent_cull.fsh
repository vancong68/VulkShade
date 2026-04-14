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
uniform sampler2D Sampler2;
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;

#include "makeup_lighting.glsl"

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec2 texCoord0;
in vec2 texCoord1;
in vec3 cameraRelativePosition;
in vec3 rawNormal;
in vec2 rawLightLevels;
in vec4 rawTintColor;

out vec4 fragColor;

void main() {
    vec4 surfaceColor = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (surfaceColor.a < 0.1) {
        discard;
    }

    vec4 vanillaLitColor = surfaceColor * lightMapColor;
    vec3 litColor = makeup_apply_entity_lighting(
        vanillaLitColor.rgb,
        surfaceColor.rgb,
        makeup_oriented_normal(rawNormal),
        rawLightLevels,
        cameraRelativePosition
    );
    litColor += texture(Sampler6, texCoord0).rgb * rawTintColor.rgb;

    fragColor = apply_fog(
        vec4(litColor, surfaceColor.a),
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );
}
