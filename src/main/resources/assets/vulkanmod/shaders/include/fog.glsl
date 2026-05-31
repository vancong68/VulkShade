float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 0.0;
    } else if (vertexDistance >= fogEnd) {
        return 1.0;
    }
    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd) {
    return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
}

float exp_height_fog_value(float vertexDistance, float viewHeight, float fogHeight, float fogDensity) {
    float heightFactor = exp(-max(viewHeight - fogHeight, 0.0) * fogDensity);
    float distFactor = 1.0 - exp(-vertexDistance * fogDensity * heightFactor);
    return clamp(distFactor, 0.0, 1.0);
}

float total_exp_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float viewHeight, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd) {
    float envFogDensity = 1.0 / max(environmantalEnd - environmentalStart, 0.001);
    float rdFogDensity = 1.0 / max(renderDistanceEnd - renderDistanceStart, 0.001);
    float envFog = exp_height_fog_value(sphericalVertexDistance, viewHeight, environmantalEnd * 0.5, envFogDensity);
    float rdFog = exp_height_fog_value(cylindricalVertexDistance, viewHeight, renderDistanceEnd * 0.5, rdFogDensity);
    return max(envFog, rdFog);
}

vec4 apply_fog(vec4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd, vec4 fogColor) {
    float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmantalEnd, renderDistanceStart, renderDistanceEnd);
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

vec4 apply_exp_fog(vec4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance, float viewHeight, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd, vec4 fogColor) {
    float fogValue = total_exp_fog_value(sphericalVertexDistance, cylindricalVertexDistance, viewHeight, environmentalStart, environmantalEnd, renderDistanceStart, renderDistanceEnd);
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

float fog_spherical_distance(vec3 pos) {
    return length(pos);
}

float fog_cylindrical_distance(vec3 pos) {
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return max(distXZ, distY);
}
