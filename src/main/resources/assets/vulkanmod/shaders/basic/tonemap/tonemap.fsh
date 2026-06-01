#version 450

layout(binding = 0) uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 aces_approx(vec3 x) {
    x = max(x, 0.0);
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec3 color = texture(Sampler0, texCoord).rgb;
    color = aces_approx(color);
    fragColor = vec4(color, 1.0);
}
