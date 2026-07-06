#version 330

#moj_import <swedenhack:skylib.glsl>

in vec2 ndc;
out vec4 fragColor;

void main() {
    vec3 dir = sky_view_dir(ndc);
    float t  = GameTime * 600.0;

    float up = clamp(dir.y, -0.15, 1.0);

    // ── Sky gradient: warm horizon → deep blue zenith ─────────────────────────
    vec3 zenith   = vec3(0.08, 0.12, 0.38);
    vec3 midsky   = vec3(0.55, 0.28, 0.55);   // purple band
    vec3 horizon  = vec3(1.00, 0.55, 0.18);   // fiery orange
    vec3 subhoriz = vec3(0.70, 0.18, 0.08);   // deep red below horizon

    vec3 col = up >= 0.0
        ? mix(horizon, mix(midsky, zenith, pow(up, 0.7)), smoothstep(0.0, 0.45, up))
        : mix(subhoriz, horizon, 1.0 + up / 0.15);

    // ── Sun disk + corona ─────────────────────────────────────────────────────
    float cycle = t * 0.04;
    vec3 sunDir = normalize(vec3(cos(cycle), 0.06 + 0.04 * sin(cycle * 0.3), sin(cycle)));
    float sd    = dot(dir, sunDir);
    float sun   = max(sd, 0.0);

    // limb-darkened disk
    col += vec3(1.0, 0.92, 0.70) * smoothstep(0.9992, 0.9998, sd);
    // inner glow
    col += vec3(1.0, 0.80, 0.45) * pow(sun, 18.0) * 0.8;
    // wide soft corona
    col += vec3(1.0, 0.65, 0.25) * pow(sun, 5.0)  * 0.4;
    // atmospheric scatter bloom
    col += vec3(0.9, 0.50, 0.15) * pow(sun, 2.5)  * 0.25 * smoothstep(0.3, 0.0, up);

    // ── Lens flare streaks ────────────────────────────────────────────────────
    vec2 sunNDC  = (ProjMat * ModelViewMat * vec4(sunDir, 0.0)).xy;
    vec2 flareUv = ndc - sunNDC;
    float fAngle = atan(flareUv.y, flareUv.x);
    float fDist  = length(flareUv);
    float streak = pow(max(cos(fAngle * 6.0), 0.0), 12.0) * exp(-fDist * 5.0) * smoothstep(0.04, 0.01, fDist);
    col += streak * vec3(1.0, 0.90, 0.60) * 0.3 * max(sd * 1.5, 0.0);

    // ── Volumetric clouds (domain-warped) ─────────────────────────────────────
    float cw = sky_warp_fbm(vec2(dir.x * 2.0 + t * 0.025, dir.z * 2.0 - t * 0.01), t);
    float cloudMask = smoothstep(-0.02, 0.25, dir.y) * smoothstep(0.45, 0.06, dir.y);

    // Illumination: clouds facing sun are bright, shadowed sides are dark
    float sunLit  = pow(max(dot(normalize(vec3(cos(cycle), 0.3, sin(cycle))), dir), 0.0), 2.0);
    vec3  cloudLit   = mix(vec3(0.30, 0.12, 0.08), vec3(1.0, 0.88, 0.72), sunLit);
    float cloudAlpha = smoothstep(0.50, 0.85, cw) * cloudMask;
    col = mix(col, cloudLit, cloudAlpha * 0.75);

    // ── Crepuscular rays ──────────────────────────────────────────────────────
    float rayAngle   = atan(dir.z - sunDir.z, dir.x - sunDir.x);
    float rayPattern = pow(max(sin(rayAngle * 14.0 + t * 0.3), 0.0), 3.0);
    float rayMask    = exp(-max(length(dir.xz - sunDir.xz), 0.0) * 3.5) * smoothstep(0.0, -0.05, dir.y);
    col += rayPattern * rayMask * vec3(1.0, 0.75, 0.35) * 0.12 * max(sd, 0.0);

    fragColor = vec4(col, 1.0);
}
