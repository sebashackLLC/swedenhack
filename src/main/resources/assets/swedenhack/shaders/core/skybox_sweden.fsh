#version 330

#moj_import <swedenhack:skylib.glsl>

in vec2 ndc;
out vec4 fragColor;

// Helper to render the waving, shaded Swedish flag
vec3 getSwedenFlag(vec2 uv, float t) {
    // Wave animation to mimic fabric in wind
    float wave = sin(uv.x * 6.28 + t * 0.006) * cos(uv.y * 6.28 + t * 0.004) * 0.04;
    vec2 waveUV = uv + vec2(wave, wave * 0.5);

    // Swedish Flag proportions for 1:1 square face:
    // Vertical bar: x from 5/16 to 7/16 (shifted left for Nordic cross look)
    // Horizontal bar: y from 4/10 to 6/10 (centered vertically)
    float x1 = 5.0 / 16.0;
    float x2 = 7.0 / 16.0;
    float y1 = 4.0 / 10.0;
    float y2 = 6.0 / 10.0;

    float crossX = smoothstep(x1 - 0.015, x1 + 0.015, waveUV.x) * (1.0 - smoothstep(x2 - 0.015, x2 + 0.015, waveUV.x));
    float crossY = smoothstep(y1 - 0.015, y1 + 0.015, waveUV.y) * (1.0 - smoothstep(y2 - 0.015, y2 + 0.015, waveUV.y));
    float cross = max(crossX, crossY);

    vec3 flagColor = mix(vec3(0.0, 0.36, 0.65), vec3(1.0, 0.80, 0.0), cross);

    // 3D flag folds lighting
    float shading = 0.78 + 0.22 * sin(waveUV.x * 12.0 - t * 0.01);
    return flagColor * shading;
}

// Map 3D view direction to flat 2D cubemap face coordinates
vec2 getCubemapUV(vec3 dir) {
    vec3 absDir = abs(dir);
    float maxAxis = max(absDir.x, max(absDir.y, absDir.z));
    vec2 uv = vec2(0.0);

    if (maxAxis == absDir.x) {
        uv = vec2(dir.z, dir.y) / absDir.x;
    } else if (maxAxis == absDir.y) {
        uv = vec2(dir.x, dir.z) / absDir.y;
    } else {
        uv = vec2(dir.x, dir.y) / absDir.z;
    }

    // Map from [-1, 1] to [0, 1]
    return uv * 0.5 + vec2(0.5);
}

void main() {
    vec3 dir = sky_view_dir(ndc);
    float t  = GameTime * 1000.0;

    // Get flat cubemap coordinates
    vec2 uv = getCubemapUV(dir);

    // Render the flag on the flat face
    vec3 col = getSwedenFlag(uv, t);

    // Twinkling stars overlay
    float star = sky_stars(dir, t, 2);
    col += star * vec3(0.4, 0.45, 0.5);

    // Contrast boost
    col = pow(clamp(col, 0.0, 1.0), vec3(0.92));

    fragColor = vec4(col, 1.0);
}
