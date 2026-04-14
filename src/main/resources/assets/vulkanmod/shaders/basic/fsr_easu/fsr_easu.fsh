#version 450
#extension GL_GOOGLE_include_directive : require

layout(binding = 1) uniform sampler2D Sampler0;

layout(binding = 0) uniform UBO {
    vec4 fsrEasuCon0;
    vec4 fsrEasuCon1;
    vec4 fsrEasuCon2;
    vec4 fsrEasuCon3;
};

layout(location = 0) in vec2 outUV;
layout(location = 0) out vec4 fragColor;

#define A_GPU 1
#define A_GLSL 1
#define FSR_EASU_F 1
#include "ffx_a.h"

AF4 FsrEasuRF(AF2 p) { return textureGather(Sampler0, p, 0); }
AF4 FsrEasuGF(AF2 p) { return textureGather(Sampler0, p, 1); }
AF4 FsrEasuBF(AF2 p) { return textureGather(Sampler0, p, 2); }

#include "ffx_fsr1.h"

void main() {
    AF3 color;
    AU2 pixelPos = AU2(uvec2(gl_FragCoord.xy));
    FsrEasuF(
        color,
        pixelPos,
        floatBitsToUint(fsrEasuCon0),
        floatBitsToUint(fsrEasuCon1),
        floatBitsToUint(fsrEasuCon2),
        floatBitsToUint(fsrEasuCon3)
    );

    fragColor = vec4(color, 1.0);
}
