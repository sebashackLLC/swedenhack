#version 330

#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

// ── Unproject NDC to world-space view direction ──────────────────────────────
vec3 sky_view_dir(vec2 ndc) {
    mat4 invVP = inverse(ProjMat * ModelViewMat);
    vec4 near = invVP * vec4(ndc, -1.0, 1.0);
    vec4 far  = invVP * vec4(ndc,  1.0, 1.0);
    return normalize(far.xyz / far.w - near.xyz / near.w);
}

// ── Hash / Noise primitives ───────────────────────────────────────────────────
float sky_hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float sky_hash3(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453123);
}

float sky_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(sky_hash(i),                sky_hash(i + vec2(1.0, 0.0)), u.x),
               mix(sky_hash(i + vec2(0.0, 1.0)), sky_hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float sky_noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(sky_hash3(i),                    sky_hash3(i + vec3(1,0,0)), u.x),
                   mix(sky_hash3(i + vec3(0,1,0)),       sky_hash3(i + vec3(1,1,0)), u.x), u.y),
               mix(mix(sky_hash3(i + vec3(0,0,1)),       sky_hash3(i + vec3(1,0,1)), u.x),
                   mix(sky_hash3(i + vec3(0,1,1)),       sky_hash3(i + vec3(1,1,1)), u.x), u.y), u.z);
}

// ── fBm variants ─────────────────────────────────────────────────────────────
float sky_fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * sky_noise(p); p *= 2.02; a *= 0.5; }
    return v;
}

float sky_fbm3(vec3 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * sky_noise3(p); p *= 2.02; a *= 0.5; }
    return v;
}

// Domain-warped fBm — much richer cloud/aurora shapes
float sky_warp_fbm(vec2 p, float t) {
    vec2 q = vec2(sky_fbm(p + vec2(0.0, 0.0)),
                  sky_fbm(p + vec2(5.2, 1.3)));
    vec2 r = vec2(sky_fbm(p + 4.0 * q + vec2(1.7 + t * 0.015, 9.2)),
                  sky_fbm(p + 4.0 * q + vec2(8.3 + t * 0.008, 2.8)));
    return sky_fbm(p + 4.0 * r);
}

// ── Star field: layered twinkle ───────────────────────────────────────────────
// Returns brightness of a star at view direction dir
// layers = number of star density layers
float sky_stars(vec3 dir, float t, int layers) {
    float out_val = 0.0;
    vec2 base = dir.xz / max(abs(dir.y) + 0.01, 0.001);
    for (int i = 0; i < layers; i++) {
        float scale = 80.0 + float(i) * 60.0;
        vec2 uv = base * scale + float(i) * 37.3;
        vec2 cell = floor(uv);
        float h = sky_hash(cell);
        float twinkle = 0.65 + 0.35 * sin(t * (3.0 + h * 5.0) + h * 6.28);
        float bright = step(0.988 - float(i) * 0.004, h) * twinkle;
        out_val = max(out_val, bright);
    }
    return out_val * smoothstep(0.0, 0.18, dir.y);
}

// ── HSV / palette helpers ─────────────────────────────────────────────────────
vec3 sky_hue(float h) {
    return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
}

vec3 sky_palette(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
    return a + b * cos(6.28318 * (c * t + d));
}
