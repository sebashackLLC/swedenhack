#version 330

#moj_import <swedenhack:skylib.glsl>

in vec2 ndc;
out vec4 fragColor;

// Complex-number squaring in 2D
vec2 csqr(vec2 z) { return vec2(z.x*z.x - z.y*z.y, 2.0*z.x*z.y); }

// Smooth-iteration count for a mandelbrot-like set
float mandel_smooth(vec2 c, int maxIter) {
    vec2 z = vec2(0.0);
    for (int i = 0; i < maxIter; i++) {
        z = csqr(z) + c;
        if (dot(z, z) > 4.0) return float(i) - log2(log2(dot(z,z)));
    }
    return float(maxIter);
}

void main() {
    vec3 dir = sky_view_dir(ndc);
    float t  = GameTime * 1200.0;

    // ── Kaleidoscopic fold of view direction ──────────────────────────────────
    float angle = atan(dir.z, dir.x);
    float fold  = 8.0;                                       // symmetry sectors
    float kAngle = mod(angle, 6.28318 / fold);
    if (mod(floor(angle / (6.28318 / fold)), 2.0) > 0.5)
        kAngle = 6.28318 / fold - kAngle;
    vec2 kDir = vec2(cos(kAngle), sin(kAngle)) * length(dir.xz);

    // ── Domain warp + fBm base ────────────────────────────────────────────────
    float warp1 = sky_warp_fbm(kDir * 3.0 + vec2(t * 0.04, dir.y), t);
    float warp2 = sky_fbm(kDir * 6.0 - vec2(dir.y * 2.0, t * 0.07));
    float warp3 = sky_fbm(vec2(warp1 * 4.0 + t * 0.02, warp2 * 4.0 - t * 0.015));

    // ── Mandelbrot-ish fractal zoom on the sky ────────────────────────────────
    float zoom  = 1.8 + 1.5 * sin(t * 0.006);
    vec2  orbit = vec2(-0.7269, 0.1889) + 0.003 * vec2(cos(t * 0.003), sin(t * 0.004));
    vec2  fracC = kDir / zoom + orbit;
    float mIter = mandel_smooth(fracC, 20);
    float fracT = mIter / 20.0;

    // ── Psychedelic colour mapping ────────────────────────────────────────────
    float h1 = fract(angle / 6.28318 + dir.y * 0.4 + warp1 * 0.5 + t * 0.018);
    float h2 = fract(warp2 * 1.5 + fracT * 0.8  - t * 0.025);
    float h3 = fract(warp3 * 2.0 + fracT * 0.4  + t * 0.012);

    vec3 c1 = sky_palette(h1,
        vec3(0.5), vec3(0.5), vec3(1.0, 0.7, 1.3), vec3(0.0, 0.15, 0.4));
    vec3 c2 = sky_palette(h2,
        vec3(0.5), vec3(0.5), vec3(0.8, 1.0, 1.0), vec3(0.3, 0.5, 0.7));
    vec3 c3 = sky_palette(h3,
        vec3(0.5), vec3(0.5), vec3(1.3, 0.9, 0.8), vec3(0.6, 0.2, 0.0));

    vec3 col = mix(c1, c2, warp1);
    col      = mix(col, c3, warp2 * 0.6);
    col      = mix(col, sky_hue(fracT + t * 0.005), smoothstep(0.6, 0.85, fracT) * 0.7);

    // ── Scanline-style pulsing rings ──────────────────────────────────────────
    float ring = sin((length(kDir) * 20.0 - t * 0.5) * 3.14159) * 0.5 + 0.5;
    col += sky_hue(h1 + 0.5) * pow(ring, 8.0) * 0.3;

    // ── Contrast / brightness boost ───────────────────────────────────────────
    col = pow(clamp(col, 0.0, 1.0), vec3(0.85));
    col *= 0.75 + 0.25 * sin(dir.y * 8.0 + t * 0.12);

    fragColor = vec4(col, 1.0);
}
