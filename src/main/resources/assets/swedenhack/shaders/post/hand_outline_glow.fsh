#version 330

uniform sampler2D InSampler;
uniform sampler2D GlowSampler;
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

layout(std140) uniform OutlineConfig {
    float FillAlpha;
    float OutlineAlpha;
    float GlowIntensity;
    int   LineWidth;
    int   GlowRadius;
    int   FillEffect;     // 0=Flat 1=Pulse 2=Rainbow 3=Fire 4=Plasma 5=Matrix 6=Glitch 7=Scan 8=Sweden
    float FillEffectSpeed;
};

in vec2 texCoord;
out vec4 fragColor;

// ── Fill-effect helpers ───────────────────────────────────────────────────────

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

float hash2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise2(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    vec2 u = f*f*(3.0-2.0*f);
    return mix(mix(hash2(i),hash2(i+vec2(1,0)),u.x),mix(hash2(i+vec2(0,1)),hash2(i+vec2(1,1)),u.x),u.y);
}
float fbm2(vec2 p){float v=0.0,a=0.5;for(int i=0;i<4;i++){v+=a*noise2(p);p*=2.01;a*=0.5;}return v;}

// ── Matrix glyph bitmaps (5×7, 12 chars: 0-9 / \) ────────────────────────
bool sampleGlyph2(int g, vec2 cellUV) {
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

vec3 applyFillEffect(vec3 base, vec2 uv, float t) {
    float s = FillEffectSpeed;
    if (FillEffect == 1) {
        return base * (0.55 + 0.45 * sin(t * s * 4.0));
    }
    if (FillEffect == 2) {
        float hue = fract(t * s * 0.08 + uv.x * 0.4 + uv.y * 0.25);
        return hsv2rgb(vec3(hue, 0.85, 1.0));
    }
    if (FillEffect == 3) {
        float n = fbm2(vec2(uv.x * 4.0 + noise2(vec2(uv.x * 3.0, t * s * 0.6)) * 0.5,
                            uv.y * 5.0 - t * s * 1.2));
        float h = clamp(n + (1.0 - uv.y) * 0.35, 0.0, 1.0);
        vec3 fire = mix(vec3(0.8,0.05,0.0),
                   mix(vec3(1.0,0.5,0.0),
                   mix(vec3(1.0,0.95,0.3), vec3(1.0), smoothstep(0.75,1.0,h)),
                       smoothstep(0.45,0.75,h)), smoothstep(0.1,0.45,h));
        return fire * (0.6 + 0.4 * n);
    }
    if (FillEffect == 4) {
        float v  = sin(uv.x*10.0+t*s*3.0)+sin(uv.y*10.0+t*s*2.3)
                 + sin((uv.x+uv.y)*8.0+t*s*1.7)
                 + sin(length(uv-0.5)*14.0-t*s*4.0);
        return hsv2rgb(vec3(fract(v*0.25+0.5+t*s*0.03), 0.9, 1.0));
    }
    if (FillEffect == 5) { // Matrix with glyph bitmaps
        float cellsX = 24.0, cellsY = 36.0;
        vec2 cellID  = floor(uv * vec2(cellsX, cellsY));
        vec2 cellUV  = fract(uv * vec2(cellsX, cellsY));
        float col    = cellID.x;
        float colSpd = 0.8 + hash2(vec2(col, 0.0)) * 1.4;
        float colOff = hash2(vec2(col, 7.3));
        float headPos  = fract(t * s * colSpd * 0.18 + colOff);
        float rowFrac  = cellID.y / cellsY;
        float dist     = fract(headPos - rowFrac + 1.0);
        float trailLen = 0.25 + hash2(vec2(col, 99.0)) * 0.30;
        if (dist > trailLen) return vec3(0.0);
        float fade = 1.0 - dist / trailLen;
        fade = fade * fade * (3.0 - 2.0 * fade);
        int glyphIdx = int(hash2(vec2(col, floor(fract(t * s * colSpd * 0.18 + colOff) * cellsY))) * 12.0);
        bool inG = sampleGlyph2(glyphIdx, cellUV);
        vec3 headC = vec3(0.85, 1.0, 0.85);
        vec3 bodyC = vec3(0.05, 1.0, 0.15);
        vec3 tailC = vec3(0.01, 0.3, 0.03);
        vec3 gc = mix(tailC, mix(bodyC, headC, smoothstep(0.7, 1.0, fade)), fade);
        return inG ? gc : tailC * fade * 0.10;
    }
    if (FillEffect == 6) {
        float band = floor(uv.y * 32.0);
        float gs = hash2(vec2(band, floor(t * s * 8.0)));
        float isG = step(0.78, gs);
        float offR = isG * (gs - 0.78) * 0.18;
        float offB = isG * (hash2(vec2(band+0.5, floor(t*s*8.0)))-0.5)*0.08;
        vec3 gr = vec3(texture(OrigSampler, texCoord+vec2(offR,0.0)).r,
                       texture(OrigSampler, texCoord).g,
                       texture(OrigSampler, texCoord+vec2(-offB,0.0)).b);
        float flash = step(0.96, hash2(vec2(band, floor(t*s*12.0))));
        return mix(gr, vec3(1.0), flash * 0.7);
    }
    if (FillEffect == 7) {
        float beam = fract(t * s * 0.25);
        float scanDist = abs(uv.y - beam);
        float scanLine = exp(-scanDist * scanDist * 600.0);
        float lines = 0.85 + 0.15 * sin(uv.y * 120.0);
        vec3 col = base * lines;
        col += scanLine * mix(base * 2.0, vec3(1.0), 0.5);
        col += hash2(vec2(floor(uv.y*180.0), floor(t*s*15.0))) * vec3(0.06);
        return col;
    }
    if (FillEffect == 8) { // Sweden
        vec2 tiling = vec2(18.0, 28.8 * (ScreenSize.y / ScreenSize.x));
        vec2 fuv = fract(uv * tiling);
        // Zoom in on the flag part of the texture (rows 24 to 103 out of 128)
        vec2 sampledUV = vec2(fuv.x, mix(0.1875, 0.8125, fuv.y));
        return texture(LogoSampler, sampledUV).rgb;
    }
    return base;
}

void main() {
    float t      = GameTime * 1000.0;
    float pulse  = 0.5 + 0.5 * sin(t * 3.0);
    float hShift = t * 0.0004;

    vec4 orig = texture(OrigSampler, texCoord);

    // ── Fill ──────────────────────────────────────────────────────────────────
    if (orig.a > 0.0) {
        if (FillAlpha > 0.0) {
            vec3 fillCol = applyFillEffect(orig.rgb, texCoord, t);
            fragColor = vec4(clamp(fillCol, 0.0, 1.0), FillAlpha);
        } else {
            fragColor = vec4(0.0);
        }
        return;
    }

    vec2 texel = 1.0 / ScreenSize;

    // ── Outline with chromatic aberration ────────────────────────────────────
    if (LineWidth > 0) {
        float caOff = float(LineWidth) * 0.003 * (0.5 + 0.5 * pulse);
        float maxAR = 0.0, maxAG = 0.0, maxAB = 0.0;
        vec3 colR = vec3(0.0), colG = vec3(0.0), colB = vec3(0.0);
        for (int y = -LineWidth; y <= LineWidth; y++) {
            vec4 sR = texture(InSampler, texCoord + texel * vec2( caOff, float(y)));
            vec4 sG = texture(InSampler, texCoord + texel * vec2( 0.0,   float(y)));
            vec4 sB = texture(InSampler, texCoord + texel * vec2(-caOff, float(y)));
            if (sR.a > maxAR) { maxAR = sR.a; colR = sR.rgb; }
            if (sG.a > maxAG) { maxAG = sG.a; colG = sG.rgb; }
            if (sB.a > maxAB) { maxAB = sB.a; colB = sB.rgb; }
        }
        float maxA = max(maxAR, max(maxAG, maxAB));
        if (maxA > 0.0) {
            vec3 caCol = vec3(colR.r * maxAR, colG.g * maxAG, colB.b * maxAB)
                       / max(vec3(maxAR, maxAG, maxAB), vec3(0.001));
            caCol = mix(mix(colG, colR, 0.5), caCol, 0.5);
            fragColor = vec4(caCol * (0.85 + 0.15 * pulse), OutlineAlpha);
            return;
        }
    }

    // ── Glow with animated breathing ─────────────────────────────────────────
    if (GlowRadius > 0) {
        float invSpan = 1.0 / float(GlowRadius + 1);
        float acc = 0.0, wSum = 0.0, maxG = 0.0;
        vec3 col = vec3(0.0);
        for (int y = -GlowRadius; y <= GlowRadius; y++) {
            vec4 s = texture(GlowSampler, texCoord + texel * vec2(0.0, float(y)));
            float tt = 1.0 - float(y) * invSpan * float(y) * invSpan;
            float w  = tt * tt;
            acc  += w * s.a; wSum += w;
            if (s.a > maxG) { maxG = s.a; col = s.rgb; }
        }
        float glow = clamp(pow(GlowIntensity * (0.8 + 0.2 * pulse) * acc / wSum, 0.72) * 1.35, 0.0, 1.0);
        if (glow > 0.0) {
            vec3 glowCol = vec3(
                col.r * (0.9 + 0.1 * sin(hShift)),
                col.g * (0.9 + 0.1 * sin(hShift + 2.094)),
                col.b * (0.9 + 0.1 * sin(hShift + 4.188))
            );
            fragColor = vec4(clamp(glowCol, 0.0, 1.0), glow);
            return;
        }
    }

    fragColor = vec4(0.0);
}
