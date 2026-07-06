#version 330

uniform sampler2D InSampler;
uniform sampler2D ColorSampler;
uniform sampler2D OrigSampler;
uniform sampler2D LogoSampler;

layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

layout(std140) uniform ChamsConfig {
    float GlowIntensity;
    float FillTint;
    float FillAlpha;
    int   GlowThickness;
    int   LineWidth;
    float RainbowSpeed;     // 0 = off
    float RainbowSat;
    float PulseSpeed;       // 0 = off
    float PulseStrength;
    float FadeStart;        // 0 = off
    float FadeEnd;
    int   FillEffect;       // 0=Flat 1=Pulse 2=Rainbow 3=Fire 4=Plasma 5=Matrix 6=Glitch 7=Scan 8=Sweden
    float FillEffectSpeed;
};

in vec2 texCoord;
out vec4 fragColor;

// ────────────────────────────────────────────────────────────────────────────
// Utilities
// ────────────────────────────────────────────────────────────────────────────

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i+vec2(1,0)), u.x),
               mix(hash(i+vec2(0,1)), hash(i+vec2(1,1)), u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) { v += a * noise(p); p *= 2.01; a *= 0.5; }
    return v;
}

// ── Propagate colour from the blurred colour buffer ───────────────────────────
vec3 propagatedColor(vec2 texel) {
    int radius = max(LineWidth, GlowThickness);
    vec3 cAcc = vec3(0.0); float wAcc = 0.0;
    for (int y = -radius; y <= radius; y++) {
        vec4 s = texture(ColorSampler, texCoord + texel * vec2(0.0, float(y)));
        cAcc += s.rgb; wAcc += s.a;
    }
    return wAcc > 0.0001 ? cAcc / wAcc : vec3(1.0);
}

// ────────────────────────────────────────────────────────────────────────────
// Fill Effect functions  (all take base entity colour + uv + time)
// ────────────────────────────────────────────────────────────────────────────

// 0 – Flat: plain tinted colour, nothing fancy
vec3 fillFlat(vec3 base, vec2 uv, float t) {
    return base;
}

// 1 – Pulse: brightness breathes in and out
vec3 fillPulse(vec3 base, vec2 uv, float t) {
    float p = 0.55 + 0.45 * sin(t * FillEffectSpeed * 4.0);
    return base * p;
}

// 2 – Rainbow: full-spectrum hue sweep
vec3 fillRainbow(vec3 base, vec2 uv, float t) {
    float hue = fract(t * FillEffectSpeed * 0.08 + uv.x * 0.4 + uv.y * 0.25);
    return hsv2rgb(vec3(hue, 0.85, 1.0));
}

// 3 – Fire: hot embers rising upward
vec3 fillFire(vec3 base, vec2 uv, float t) {
    float speed = FillEffectSpeed;
    // turbulent upward scroll
    float n = fbm(vec2(uv.x * 4.0 + noise(vec2(uv.x * 3.0, t * speed * 0.6)) * 0.5,
                       uv.y * 5.0 - t * speed * 1.2));
    // cool base → orange → yellow → white tip
    float h = n + (1.0 - uv.y) * 0.35;
    h = clamp(h, 0.0, 1.0);
    vec3 fire = mix(vec3(0.8, 0.05, 0.0),
               mix(vec3(1.0, 0.5, 0.0),
               mix(vec3(1.0, 0.95, 0.3),
                   vec3(1.0, 1.0, 1.0), smoothstep(0.75, 1.0, h)),
                   smoothstep(0.45, 0.75, h)),
                   smoothstep(0.1, 0.45, h));
    return fire * (0.6 + 0.4 * n);
}

// 4 – Plasma: classic psychedelic sine-wave colour field
vec3 fillPlasma(vec3 base, vec2 uv, float t) {
    float s = FillEffectSpeed;
    float v  = sin(uv.x * 10.0 + t * s * 3.0);
    v += sin(uv.y * 10.0 + t * s * 2.3);
    v += sin((uv.x + uv.y) * 8.0 + t * s * 1.7);
    v += sin(length(uv - 0.5) * 14.0 - t * s * 4.0);
    v = v * 0.25 + 0.5;
    float hue = fract(v + t * s * 0.03);
    return hsv2rgb(vec3(hue, 0.9, 1.0));
}

// 5 – Matrix: falling green characters with per-cell glyph bitmaps
//
// Technique: each cell gets a deterministic "character index" from hash.
// A 5x7 bitmap per character (packed into 5 ints) is sampled to determine
// if the current pixel is inside a glyph stroke, giving crisp letter shapes.
//
// Glyph set: digits 0-9 + select katakana-like strokes (12 total),
// packed as 5 columns × 7 rows = 35 bits per glyph, stored in 2 uints.

// ── Matrix glyph bitmaps (5×7, 12 chars: 0-9 / \) ───────────────────────────
bool sampleGlyph(int g, vec2 cellUV) {
    int cx = int(clamp(cellUV.x * 5.0, 0.0, 4.99));
    int cy = int(clamp(cellUV.y * 7.0, 0.0, 6.99));
    int[5] c;
    if      (g == 0)  c = int[5](0x7E,0x42,0x42,0x42,0x7E);
    else if (g == 1)  c = int[5](0x00,0x44,0x7E,0x40,0x00);
    else if (g == 2)  c = int[5](0x62,0x52,0x52,0x52,0x4E);
    else if (g == 3)  c = int[5](0x22,0x42,0x4A,0x4A,0x36);
    else if (g == 4)  c = int[5](0x1E,0x10,0x10,0x7E,0x10);
    else if (g == 5)  c = int[5](0x4E,0x4A,0x4A,0x4A,0x32);
    else if (g == 6)  c = int[5](0x3C,0x4A,0x4A,0x4A,0x30);
    else if (g == 7)  c = int[5](0x02,0x02,0x72,0x0A,0x06);
    else if (g == 8)  c = int[5](0x36,0x49,0x49,0x49,0x36);
    else if (g == 9)  c = int[5](0x06,0x49,0x49,0x49,0x3E);
    else if (g == 10) c = int[5](0x40,0x20,0x10,0x08,0x04);
    else              c = int[5](0x04,0x08,0x10,0x20,0x40);
    return ((c[cx] >> cy) & 1) != 0;
}

// 5 – Matrix: falling characters with per-cell bitmap glyphs
vec3 fillMatrix(vec3 base, vec2 uv, float t) {
    float s = FillEffectSpeed;
    float cellsX = 24.0, cellsY = 36.0;
    vec2  cellID  = floor(uv * vec2(cellsX, cellsY));
    vec2  cellUV  = fract(uv * vec2(cellsX, cellsY));
    float col     = cellID.x;
    float colSpd  = 0.8 + hash(vec2(col, 0.0)) * 1.4;
    float colOff  = hash(vec2(col, 7.3));
    float headPos = fract(t * s * colSpd * 0.18 + colOff);
    float rowFrac = cellID.y / cellsY;
    float dist    = fract(headPos - rowFrac + 1.0);
    float trailLen = 0.25 + hash(vec2(col, 99.0)) * 0.30;
    if (dist > trailLen) return vec3(0.0);
    float fade = 1.0 - dist / trailLen;
    fade = fade * fade * (3.0 - 2.0 * fade);
    int glyphIdx = int(hash(vec2(col, floor(headPos * cellsY))) * 12.0);
    bool inG = sampleGlyph(glyphIdx, cellUV);
    vec3 headC = vec3(0.85, 1.0, 0.85);
    vec3 bodyC = vec3(0.05, 1.0, 0.15);
    vec3 tailC = vec3(0.01, 0.3, 0.03);
    vec3 gc = mix(tailC, mix(bodyC, headC, smoothstep(0.7, 1.0, fade)), fade);
    return inG ? gc : tailC * fade * 0.10;
}

// 6 – Glitch: horizontal RGB-offset tearing blocks
vec3 fillGlitch(vec3 base, vec2 uv, float t) {
    float speed = FillEffectSpeed;
    // Slice the screen into horizontal bands
    float band = floor(uv.y * 32.0);
    float glitchSeed = hash(vec2(band, floor(t * speed * 8.0)));
    float isGlitch = step(0.78, glitchSeed);

    // RGB channel offsets within glitch bands
    float offR = isGlitch * (glitchSeed - 0.78) * 0.18;
    float offB = isGlitch * (hash(vec2(band + 0.5, floor(t * speed * 8.0))) - 0.5) * 0.08;

    vec4 cR = texture(OrigSampler, texCoord + vec2( offR, 0.0));
    vec4 cG = texture(OrigSampler, texCoord);
    vec4 cB = texture(OrigSampler, texCoord + vec2(-offB, 0.0));

    vec3 glitched = vec3(cR.r, cG.g, cB.b) * FillTint;
    // Occasional bright white flash across the band
    float flash = step(0.96, hash(vec2(band, floor(t * speed * 12.0))));
    glitched = mix(glitched, vec3(1.0), flash * 0.7);
    return glitched;
}

// 7 – Scan: sweeping horizontal scan-line highlight
vec3 fillScan(vec3 base, vec2 uv, float t) {
    float speed = FillEffectSpeed;
    // Main scan beam
    float beam = fract(t * speed * 0.25);
    float scanDist = abs(uv.y - beam);
    float scanLine = exp(-scanDist * scanDist * 600.0);

    // Secondary dim scan lines
    float lines = 0.85 + 0.15 * sin(uv.y * 120.0);

    vec3 col = base * lines;
    // Beam colour: bright version of entity colour
    col += scanLine * mix(base * 2.0, vec3(1.0), 0.5);
    // faint horizontal noise bands
    float noise_v = hash(vec2(floor(uv.y * 180.0), floor(t * speed * 15.0))) * 0.06;
    col += vec3(noise_v);
    return col;
}

// 8 – Sweden: Swedish flag pattern rendered from logo.png
vec3 fillSweden(vec3 base, vec2 uv, float t) {
    vec2 tiling = vec2(18.0, 28.8 * (ScreenSize.y / ScreenSize.x));
    vec2 fuv = fract(uv * tiling);
    // Zoom in on the flag part of the texture (rows 24 to 103 out of 128)
    vec2 sampledUV = vec2(fuv.x, mix(0.1875, 0.8125, fuv.y));
    return texture(LogoSampler, sampledUV).rgb;
}

// ────────────────────────────────────────────────────────────────────────────
// Main
// ────────────────────────────────────────────────────────────────────────────

void main() {
    vec2 texel = 1.0 / ScreenSize;
    vec4 center = texture(OrigSampler, texCoord);

    float t = GameTime * 1000.0;

    // ── Outline/glow pulse multiplier ────────────────────────────────────────
    float pulseMul = 1.0;
    if (PulseSpeed > 0.0) {
        float p = 0.5 + 0.5 * sin(t * PulseSpeed * 0.006283);
        pulseMul = 1.0 - PulseStrength + PulseStrength * p;
    }

    // ── Outline rainbow override ──────────────────────────────────────────────
    bool doRainbow = RainbowSpeed > 0.0;
    vec3 rainbowCol = vec3(1.0);
    if (doRainbow) {
        float hue = fract(t * RainbowSpeed * 0.0001 + texCoord.x * 0.3 + texCoord.y * 0.2);
        rainbowCol = hsv2rgb(vec3(hue, RainbowSat, 1.0));
    }

    // ── FILL ─────────────────────────────────────────────────────────────────
    if (center.a > 0.0) {
        vec3 base = center.rgb * FillTint;
        vec3 fillCol;
        if      (FillEffect == 1) fillCol = fillPulse  (base, texCoord, t);
        else if (FillEffect == 2) fillCol = fillRainbow(base, texCoord, t);
        else if (FillEffect == 3) fillCol = fillFire   (base, texCoord, t);
        else if (FillEffect == 4) fillCol = fillPlasma (base, texCoord, t);
        else if (FillEffect == 5) fillCol = fillMatrix (base, texCoord, t);
        else if (FillEffect == 6) fillCol = fillGlitch (base, texCoord, t);
        else if (FillEffect == 7) fillCol = fillScan   (base, texCoord, t);
        else if (FillEffect == 8) fillCol = fillSweden (base, texCoord, t);
        else                      fillCol = fillFlat   (base, texCoord, t);

        fragColor = vec4(clamp(fillCol, 0.0, 1.0), center.a * FillAlpha);
        return;
    }

    // ── Propagate outline colour ──────────────────────────────────────────────
    vec3 col = propagatedColor(texel);
    if (doRainbow) col = mix(col, rainbowCol, 0.75);

    // ── Distance fade ─────────────────────────────────────────────────────────
    float fadeMul = 1.0;
    if (FadeStart > 0.0 && FadeEnd > FadeStart) {
        float dAccum = 0.0; float dCount = 0.0;
        int sr = max(LineWidth, GlowThickness);
        for (int y = -sr; y <= sr; y += max(1, sr/3)) {
            vec4 s = texture(OrigSampler, texCoord + texel * vec2(0.0, float(y)));
            if (s.a > 0.0) { dAccum += s.a; dCount += 1.0; }
        }
        if (dCount > 0.0) {
            float coverage = dAccum / dCount;
            float estDist = mix(FadeEnd, FadeStart, clamp(coverage, 0.0, 1.0));
            fadeMul = 1.0 - clamp((estDist - FadeStart) / (FadeEnd - FadeStart), 0.0, 1.0);
        }
    }

    // ── Outline ───────────────────────────────────────────────────────────────
    if (LineWidth > 0) {
        float maxA = 0.0;
        for (int y = -LineWidth; y <= LineWidth; y++)
            maxA = max(maxA, texture(InSampler, texCoord + texel * vec2(0.0, float(y))).r);

        if (maxA > 0.0) {
            float caOff = float(LineWidth) * 0.004 * pulseMul;
            float aR = 0.0, aG = 0.0, aB = 0.0;
            for (int y = -LineWidth; y <= LineWidth; y++) {
                aR = max(aR, texture(InSampler, texCoord + texel * vec2( caOff, float(y))).r);
                aG = max(aG, texture(InSampler, texCoord + texel * vec2( 0.0,   float(y))).r);
                aB = max(aB, texture(InSampler, texCoord + texel * vec2(-caOff, float(y))).r);
            }
            vec3 outCol = vec3(mix(col.r,1.0,0.2)*aR, col.g*aG, mix(col.b,1.0,0.2)*aB) * pulseMul;
            fragColor = vec4(clamp(outCol, 0.0, 1.0), maxA * fadeMul);
            return;
        }
    }

    // ── Glow ──────────────────────────────────────────────────────────────────
    if (GlowThickness > 0) {
        float invSpan = 1.0 / float(GlowThickness + 1);
        float acc = 0.0, wSum = 0.0;
        for (int y = -GlowThickness; y <= GlowThickness; y++) {
            float tt = 1.0 - float(y) * invSpan * float(y) * invSpan;
            float w  = tt * tt;
            acc  += w * texture(InSampler, texCoord + texel * vec2(0.0, float(y))).g;
            wSum += w;
        }
        float glow = clamp(pow(GlowIntensity * pulseMul * acc / wSum, 0.72) * 1.35, 0.0, 1.0);
        if (glow > 0.0) {
            float hOff = t * 0.0003;
            vec3 glowCol = vec3(col.r*(0.88+0.12*sin(hOff)),
                                col.g*(0.88+0.12*sin(hOff+2.094)),
                                col.b*(0.88+0.12*sin(hOff+4.188)));
            if (doRainbow) glowCol = mix(glowCol, rainbowCol, 0.6);
            fragColor = vec4(clamp(glowCol, 0.0, 1.0), glow * fadeMul);
            return;
        }
    }

    fragColor = vec4(0.0);
}
