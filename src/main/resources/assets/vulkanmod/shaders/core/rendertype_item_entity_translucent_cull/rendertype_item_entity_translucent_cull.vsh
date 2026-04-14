#version 330

#include "light.glsl"
#include "fog.glsl"

layout(std140) uniform Lighting {
vec3 Light0_Direction;
vec3 Light1_Direction;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};

layout(std140) uniform Projection {
    mat4 ProjMat;
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

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;
uniform sampler2D Sampler5;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec2 texCoord0;
out vec2 texCoord1;
out vec2 texCoord2;
out vec3 cameraRelativePosition;
out vec3 rawNormal;
out vec2 rawLightLevels;
out vec4 rawTintColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    rawLightLevels = clamp(vec2(UV2 / 16) / 15.0, 0.0, 1.0);

    texCoord0 = UV0;
    texCoord1 = UV1;
    texCoord2 = UV2;
    cameraRelativePosition = Position;
    rawNormal = normalize(Normal);
    rawTintColor = Color * ColorModulator;
}
