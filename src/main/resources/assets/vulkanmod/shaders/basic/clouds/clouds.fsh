#version 450

#include "fog.glsl"

layout(binding = 1) uniform UBO {
    vec4 ColorModulator;
    float FogCloudsEnd;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in float vertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = vertexColor;
    float altitudeFade = clamp(1.0 - vertexDistance / FogCloudsEnd, 0.2, 1.0);
    color.rgb *= mix(0.85, 1.15, altitudeFade);
    color.a *= 1.0f - linear_fog_value(vertexDistance, 0, FogCloudsEnd);
    fragColor = vec4(color.rgb, color.a);
}
