package dev.leonetic.features.modules.render;

import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.client.gui.GuiGraphics;

import java.awt.Color;
import java.util.Random;

/**
 * MatrixMode — fullscreen Matrix rain.
 *
 * Performance design:
 *  - Trail = ONE gradient rect per column  (2 draw calls if wrap, 1 otherwise)
 *  - Characters = HEAD ONLY — 1 drawString per column
 *  - Total at 1920px: ~200 gradient calls + ~200 text calls = ~400 total
 *  - Secondary stream uses gradient only (no extra text calls)
 *
 * Characters also appear in the trail via the "LetterTrail" mode which draws
 * every N rows to keep total text calls capped at MaxTrailChars.
 */
public class ScreenEffectsModule extends Module {

    // ── Settings ─────────────────────────────────────────────────────────────
    public final Setting<Float>   alpha      = num("Alpha",          0.7f, 0.05f, 1.0f);
    public final Setting<Float>   speed      = num("Speed",          1.0f, 0.1f,  4.0f);
    public final Setting<Color>   color      = color("Color",        0, 220, 60, 255);
    /** How many text chars to draw per column in the trail (0 = gradient only) */
    public final Setting<Integer> trailChars = num("TrailChars",     4,    0,     12);

    public ScreenEffectsModule() {
        super("MatrixMode", "Fullscreen Matrix rain overlay.", Category.RENDER);
    }

    // ── Glyph table ───────────────────────────────────────────────────────────
    private static final char[] GLYPHS;
    static {
        String s = "ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"
                + "0123456789@#$%&*?/<>|~^";
        GLYPHS = s.toCharArray();
    }

    // ── Per-column state ──────────────────────────────────────────────────────
    private int     cachedW = -1, cachedH = -1;
    private int     numCols, numRows, cellW, cellH;
    private float[] headA, headB;
    private float[] spdA,  spdB;
    private int[]   trailA, trailB;
    // Glyph per (col, row) — only top rows near head are randomised each flicker
    private int[][] glyphs;
    private long    lastFlicker;
    // Reusable single-char string buffer to avoid String allocation per drawString
    private final char[] charBuf = new char[1];

    private void rebuild(int w, int h) {
        cellH = mc.font.lineHeight + 1;
        cellW = mc.font.width("ﾝ") + 1;
        numCols = w / cellW + 1;
        numRows = h / cellH + 2;
        cachedW = w; cachedH = h;

        Random rng = new Random(0xDEADC0DE);
        headA  = new float[numCols]; headB  = new float[numCols];
        spdA   = new float[numCols]; spdB   = new float[numCols];
        trailA = new int[numCols];   trailB = new int[numCols];
        glyphs = new int[numCols][numRows];

        for (int c = 0; c < numCols; c++) {
            headA[c]  = rng.nextFloat() * numRows;
            headB[c]  = (headA[c] + numRows * 0.55f) % numRows;
            spdA[c]   = 3.0f + rng.nextFloat() * 5.5f;
            spdB[c]   = 2.5f + rng.nextFloat() * 4.5f;
            trailA[c] = (int)(numRows * (0.22f + rng.nextFloat() * 0.28f));
            trailB[c] = (int)(numRows * (0.12f + rng.nextFloat() * 0.18f));
            for (int r = 0; r < numRows; r++)
                glyphs[c][r] = rng.nextInt(GLYPHS.length);
        }
        lastFlicker = 0;
    }

    // ── Render ────────────────────────────────────────────────────────────────
    @Subscribe
    private void onRender(Render2DEvent event) {
        if (nullCheck()) return;
        GuiGraphics ctx = event.getContext();
        int w = ctx.guiWidth();
        int h = ctx.guiHeight();
        if (w != cachedW || h != cachedH || headA == null) rebuild(w, h);

        // Flicker: only randomise cells near each head, max 4 rows per column
        long now = System.currentTimeMillis();
        if (now - lastFlicker > 120) {
            lastFlicker = now;
            // Use a fast LCG instead of new Random() — no object allocation
            long seed = now ^ 0xCAFEBABEL;
            for (int c = 0; c < numCols; c++) {
                int hA = (int) headA[c];
                for (int dr = 0; dr < 4; dr++) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                    int row = ((hA - dr) % numRows + numRows) % numRows;
                    if ((seed & 1) == 0) glyphs[c][row] = (int)((seed >>> 32) % GLYPHS.length);
                }
            }
        }

        // Advance heads using delta time
        float dt = event.getDelta() * speed.getValue() / 20.0f;
        for (int c = 0; c < numCols; c++) {
            headA[c] = (headA[c] + spdA[c] * dt) % numRows;
            headB[c] = (headB[c] + spdB[c] * dt) % numRows;
        }

        float a    = alpha.getValue();
        Color base = color.getValue();
        int   br   = base.getRed(), bg = base.getGreen(), bb = base.getBlue();
        int   tc   = trailChars.getValue();

        for (int col = 0; col < numCols; col++) {
            int x = col * cellW;
            drawStream(ctx, col, x, h, headA[col], trailA[col], a,      br, bg, bb, true,  tc);
            drawStream(ctx, col, x, h, headB[col], trailB[col], a*0.5f, br, bg, bb, false, 0);
        }
    }

    /**
     * Draw one stream.
     * - 1-2 gradient rect for trail
     * - 1 fill rect for head glow (primary only)
     * - 1 drawString for head char
     * - up to `maxTextChars` drawString for trail chars (spaced evenly to cap cost)
     */
    private void drawStream(GuiGraphics ctx, int col, int x, int h,
                             float headRow, int trailLen,
                             float a, int br, int bg, int bb,
                             boolean isPrimary, int maxTextChars) {
        int headR = (int) headRow;
        int headY = headR * cellH;

        // Clip streams that are entirely off-screen
        if (headY + cellH < 0) return;

        // ── Trail gradient ────────────────────────────────────────────────────
        int tailY = (headR - trailLen) * cellH;
        int trailBright = argb((int)(180 * a), (int)(br*0.15f), bg, (int)(bb*0.15f));
        int clear = 0;
        if (tailY >= 0) {
            vgrad(ctx, x, tailY, x + cellW - 1, headY, clear, trailBright);
        } else {
            vgrad(ctx, x, tailY + h, x + cellW - 1, h,     clear, trailBright);
            vgrad(ctx, x, 0,         x + cellW - 1, headY, clear, trailBright);
        }

        // ── Head glow (primary only) ──────────────────────────────────────────
        if (isPrimary && headY >= -cellH && headY < h) {
            ctx.fill(x - 1, headY - 1, x + cellW + 1, headY + cellH + 1,
                    argb((int)(50 * a), (int)(br*0.1f), bg, (int)(bb*0.1f)));
        }

        // ── Head character ────────────────────────────────────────────────────
        if (headY >= 0 && headY < h) {
            int headColor = argb((int)(255 * a),
                    mix(br, 255, 0.85f), mix(bg, 255, 0.85f), mix(bb, 255, 0.85f));
            charBuf[0] = GLYPHS[glyphs[col][headR % numRows]];
            ctx.drawString(mc.font, new String(charBuf), x, headY, headColor, false);
        }

        // ── Trail characters (evenly spaced, capped at maxTextChars) ─────────
        // Stride = trailLen / maxTextChars → draw one char every `stride` rows
        if (maxTextChars > 0 && trailLen > 1) {
            float stride = (float) trailLen / maxTextChars;
            for (int i = 1; i <= maxTextChars; i++) {
                int dr  = Math.round(i * stride);
                int row = ((headR - dr) % numRows + numRows) % numRows;
                int y   = row * cellH;
                if (y < 0 || y >= h) continue;

                // Fade: quadratic from bright near head to invisible at tail
                float fade = 1.0f - (float) dr / trailLen;
                float sqf  = fade * fade;
                int   ca   = (int)(170 * a * sqf);
                if (ca < 6) continue;
                int gVal = (int)(bg * (0.25f + 0.75f * fade));
                int tc_color = argb(ca, (int)(br*0.05f * fade), gVal, (int)(bb*0.05f * fade));

                charBuf[0] = GLYPHS[glyphs[col][row]];
                ctx.drawString(mc.font, new String(charBuf), x, y, tc_color, false);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Vertical gradient: top → bottom */
    private static void vgrad(GuiGraphics ctx, int x1, int y1, int x2, int y2,
                               int top, int bot) {
        if (y1 >= y2) return;
        RenderUtil.gradient(ctx, x1, y1, x2, y2, top, bot, bot, top);
    }

    private static int argb(int a, int r, int g, int b) {
        return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    private static int mix(int a, int b, float t) {
        return clamp(a + (int)((b - a) * t));
    }
}
