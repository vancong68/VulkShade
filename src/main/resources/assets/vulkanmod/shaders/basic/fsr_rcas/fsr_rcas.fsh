#version 450
#extension GL_GOOGLE_include_directive : require

layout(binding = 1) uniform sampler2D Sampler0;

layout(binding = 0) uniform UBO {
    vec4 fsrRcasCon;
};

layout(location = 0) in vec2 outUV;
layout(location = 0) out vec4 fragColor;

#define A_GPU 1
#define A_GLSL 1
#define FSR_RCAS_F 1
#include "ffx_a.h"

AF4 FsrRcasLoadF(ASU2 p) { return texelFetch(Sampler0, ASU2(p), 0); }
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}

#include "ffx_fsr1.h"

void main() {
    AF1 r;
    AF1 g;
    AF1 b;
    AU2 pixelPos = AU2(uvec2(gl_FragCoord.xy));
    FsrRcasF(r, g, b, pixelPos, floatBitsToUint(fsrRcasCon));
    fragColor = vec4(r, g, b, 1.0);
}
