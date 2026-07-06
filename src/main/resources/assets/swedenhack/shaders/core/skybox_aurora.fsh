#version 330

#moj_import <swedenhack:skylib.glsl>

in vec2 ndc;
in vec4 vColor;
out vec4 fragColor;

void main() {
    vec3 dir = sky_view_dir(ndc);
    float t  = GameTime * 1000.0;

    // ── Base night sky ────────────────────────────────────────────────────────
    float up  = clamp(dir.y, 0.0, 1.0);
    float up2 = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);

    // Deep blue-to-black gradient with a faint deep-violet belt near the horizon
    vec3 zenith  = vec3(0.01, 0.02, 0.06);
    vec3 midsky  = vec3(0.03, 0.04, 0.10);
    vec3 horizon = vec3(0.05, 0.03, 0.08);
    vec3 col = mix(horizon, mix(midsky, zenith, up), smoothstep(0.0, 0.4, dir.y));

    // ── Multi-layer twinkling stars ───────────────────────────────────────────
    float star = sky_stars(dir, t, 3);
    col += star * vec3(0.85, 0.90, 1.0);

    // Occasional coloured stars (red / blue giants)
    vec2 cStarUv = dir.xz / max(abs(dir.y) + 0.01, 0.001) * 250.0;
    float ch = sky_hash(floor(cStarUv));
    float cStar = step(0.9985, ch) * smoothstep(0.0, 0.2, dir.y);
    col += cStar * sky_hue(ch * 2.5) * 0.7;

    // ── Aurora curtains (domain-warped, multi-colour) ─────────────────────────
    vec3 auroraCol  = vColor.rgb;                          // user colour from CPU
    float horizMask = smoothstep(0.0, 0.55, dir.y);

    // Primary curtain — slow, wide
    float band1 = sky_warp_fbm(vec2(dir.x * 2.5 + t * 0.03, dir.y * 5.0 - t * 0.015), t);
    float a1    = smoothstep(0.42, 0.92, band1) * horizMask;

    // Secondary curtain — faster, narrower, offset hue
    float band2 = sky_warp_fbm(vec2(dir.x * 4.0 - t * 0.05, dir.y * 8.0 + t * 0.02 + 3.7), t);
    float a2    = smoothstep(0.50, 0.95, band2) * horizMask * 0.55;

    // Colour-shift across the curtain using palette
    vec3 aC1 = sky_palette(band1 + t * 0.003,
                            vec3(0.5), vec3(0.5),
                            vec3(1.0, 1.0, 1.0),
                            auroraCol * 0.5 + vec3(0.0, 0.33, 0.67));
    vec3 aC2 = sky_palette(band2 - t * 0.004,
                            vec3(0.5), vec3(0.5),
                            vec3(1.0, 1.0, 1.0),
                            auroraCol * 0.5 + vec3(0.33, 0.0, 0.67));

    col += a1 * mix(auroraCol * 0.3, aC1, horizMask);
    col += a1 * a1 * mix(aC1, vec3(1.0), 0.4);          // core bright line
    col += a2 * aC2 * 0.7;
    col += a2 * a2 * aC2;

    // ── Milky Way band ────────────────────────────────────────────────────────
    float mwAngle = atan(dir.z, dir.x) / 6.28318 + 0.5;
    float mw = sky_fbm(vec2(mwAngle * 8.0 + t * 0.001, dir.y * 6.0));
    mw = smoothstep(0.55, 0.75, mw) * smoothstep(0.05, 0.4, abs(dir.y)) * 0.18;
    col += mw * vec3(0.55, 0.60, 0.80);

    fragColor = vec4(col, 1.0);
}
