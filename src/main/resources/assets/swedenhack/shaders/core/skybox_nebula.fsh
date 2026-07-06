#version 330

#moj_import <swedenhack:skylib.glsl>

in vec2 ndc;
out vec4 fragColor;

void main() {
    vec3 dir = sky_view_dir(ndc);
    float t  = GameTime * 400.0;

    // ── Deep-space base ───────────────────────────────────────────────────────
    vec3 col = vec3(0.01, 0.0, 0.03);

    // ── Layered nebula clouds via domain warp ─────────────────────────────────
    // Use 3-D noise so the nebula looks different in every direction
    vec3 p = dir;
    float a = sky_fbm3(p * 1.4 + vec3(t * 0.012, 0.0, 0.0));
    float b = sky_fbm3(p * 2.8 - vec3(0.0, t * 0.008, t * 0.005));
    float c = sky_fbm3(p * 5.5 + vec3(t * 0.006, t * 0.009, 0.0));

    // Palette-driven nebula colours (magenta/teal/orange)
    vec3 neb1 = sky_palette(a + t * 0.002,
        vec3(0.5, 0.1, 0.4), vec3(0.4, 0.3, 0.5),
        vec3(1.0, 0.8, 1.2), vec3(0.0, 0.3, 0.6));
    vec3 neb2 = sky_palette(b - t * 0.003,
        vec3(0.1, 0.4, 0.5), vec3(0.3, 0.4, 0.3),
        vec3(0.8, 1.0, 0.9), vec3(0.6, 0.1, 0.3));
    vec3 neb3 = sky_palette(c * 0.5,
        vec3(0.6, 0.3, 0.1), vec3(0.4, 0.3, 0.2),
        vec3(1.0, 1.0, 0.8), vec3(0.3, 0.5, 0.1));

    col += neb1 * pow(a, 1.3) * 0.7;
    col += neb2 * pow(b, 2.0) * 0.5;
    col += neb3 * pow(c, 3.0) * 0.4;

    // ── Emission filaments (bright narrow wisps) ──────────────────────────────
    float filament = sky_fbm3(p * 12.0 + vec3(t * 0.02));
    filament = pow(clamp(filament - 0.6, 0.0, 1.0) * 2.5, 2.5);
    col += filament * vec3(0.9, 0.85, 1.0) * 0.35;

    // ── Stars: 4 layers, twinkling ────────────────────────────────────────────
    float star = sky_stars(dir, t, 4);
    col += star * vec3(1.0, 0.98, 0.95);

    // Faint dust-lane darkening along the galactic plane
    float dustAngle = atan(dir.z, dir.x) / 6.28318 + 0.5;
    float dust = sky_fbm(vec2(dustAngle * 10.0 + t * 0.001, dir.y * 4.0));
    float dustMask = smoothstep(0.62, 0.75, dust) * smoothstep(0.3, 0.0, abs(dir.y));
    col *= 1.0 - dustMask * 0.55;

    fragColor = vec4(col, 1.0);
}
