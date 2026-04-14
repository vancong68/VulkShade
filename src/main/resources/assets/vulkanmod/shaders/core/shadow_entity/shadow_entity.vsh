#version 330

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

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec2 texCoord0;
out vec4 vertexColor;

const float SHADOW_DIST = 0.83;

void main() {
    vec4 shadowPos = ProjMat * ModelViewMat * vec4(Position, 1.0);
    float dist = length(shadowPos.xy);
    float distortion = dist * SHADOW_DIST + (1.0 - SHADOW_DIST);

    shadowPos.xy /= distortion;
    shadowPos.z *= 0.2;
    gl_Position = shadowPos;

    texCoord0 = UV0;
#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif

    vertexColor = Color * ColorModulator;
}
