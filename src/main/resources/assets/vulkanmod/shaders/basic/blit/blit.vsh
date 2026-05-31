#version 450

const vec4 pos[] = { vec4(-1, -1, 0, 1),  vec4(3, -1, 0, 1), vec4(-1, 3, 0, 1) };

// Note: With Vulkan's inverted viewport (negative height), UV coordinates must
// account for the Y-flip that happens during barycentric interpolation.
// NDC y = -1 maps to the bottom of the viewport, so UV.y = 1 there gives
// correct texture sampling (texture origin is upper-left).
const vec2 uv[] = { vec2(0, 1),  vec2(2, 1), vec2(0, -1) };

layout(location = 0) out vec2 outUV;

void main() {
    outUV = uv[gl_VertexIndex];
    gl_Position = pos[gl_VertexIndex];
}