package dev.leonetic.util.render;

import dev.leonetic.Swedenhack;
import dev.leonetic.features.modules.render.ShadersModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class HandShaderChain {

    private static final Identifier SCREENQUAD        = Identifier.fromNamespaceAndPath("minecraft", "core/screenquad");
    private static final Identifier DILATE_H_FSH      = Identifier.fromNamespaceAndPath("swedenhack", "post/hand_outline_h");
    private static final Identifier OUTLINE_FSH       = Identifier.fromNamespaceAndPath("swedenhack", "post/hand_outline");
    private static final Identifier GLOW_H_FSH        = Identifier.fromNamespaceAndPath("swedenhack", "post/hand_glow_h");
    private static final Identifier OUTLINE_GLOW_FSH  = Identifier.fromNamespaceAndPath("swedenhack", "post/hand_outline_glow");
    private static final Identifier DILATED           = Identifier.fromNamespaceAndPath("swedenhack", "hand_dilated");
    private static final Identifier GLOW_H            = Identifier.fromNamespaceAndPath("swedenhack", "hand_glow_h");
    private static final Identifier CHAIN_NAME        = Identifier.fromNamespaceAndPath("swedenhack", "hand_shader_runtime");

    private static CachedOrthoProjectionMatrixBuffer projection;
    private static PostChain cached;
    private static int   lastLineWidth      = Integer.MIN_VALUE;
    private static float lastFillAlpha      = Float.NaN;
    private static int   lastGlowRadius     = Integer.MIN_VALUE;
    private static float lastGlowIntensity  = Float.NaN;
    private static int   lastFillEffect     = Integer.MIN_VALUE;
    private static float lastFillEffectSpd  = Float.NaN;

    private HandShaderChain() {}

    public static PostChain get(boolean outline, int thickness, boolean fill,
                                boolean glow, int glowRadius, float glowIntensity) {
        int lineWidth = outline ? Math.max(1, thickness) : 0;
        int glowR = glow ? Math.max(0, glowRadius) : 0;

        // Pull fill-effect settings from ShadersModule
        int fillEffect = 0;
        float fillEffectSpd = 1f;
        float fillAlpha = fill ? 0.35f : 0.0f;
        ShadersModule sh = Swedenhack.moduleManager != null
                ? Swedenhack.moduleManager.getModuleByClass(ShadersModule.class) : null;
        if (sh != null) {
            fillEffect    = sh.getHandFillEffectOrdinal();
            fillEffectSpd = sh.getHandFillEffectSpeed();
            fillAlpha     = sh.getHandFillAlpha();
        }

        if (lineWidth == 0 && fillAlpha <= 0f && glowR == 0) return null;

        if (cached != null
                && lineWidth      == lastLineWidth
                && fillAlpha      == lastFillAlpha
                && glowR          == lastGlowRadius
                && glowIntensity  == lastGlowIntensity
                && fillEffect     == lastFillEffect
                && fillEffectSpd  == lastFillEffectSpd) {
            return cached;
        }

        PostChain rebuilt = build(lineWidth, fillAlpha, glowR, glowIntensity, fillEffect, fillEffectSpd);
        if (rebuilt == null) return cached;
        if (cached != null) cached.close();
        cached = rebuilt;
        lastLineWidth     = lineWidth;
        lastFillAlpha     = fillAlpha;
        lastGlowRadius    = glowR;
        lastGlowIntensity = glowIntensity;
        lastFillEffect    = fillEffect;
        lastFillEffectSpd = fillEffectSpd;
        return cached;
    }

    private static PostChain build(int lineWidth, float fillAlpha,
                                   int glowRadius, float glowIntensity,
                                   int fillEffect, float fillEffectSpd) {
        try {
            if (projection == null) {
                projection = new CachedOrthoProjectionMatrixBuffer("swedenhack_hand", 0.1f, 1000.0f, false);
            }

            PostChainConfig.Pass dilateH = new PostChainConfig.Pass(
                    SCREENQUAD, DILATE_H_FSH,
                    List.of(new PostChainConfig.TargetInput("In", PostChain.MAIN_TARGET_ID, false, false)),
                    DILATED,
                    Map.of("DilateConfig", List.<UniformValue>of(integer(lineWidth))));

            PostChainConfig config = glowRadius > 0
                    ? buildGlow(dilateH, fillAlpha, lineWidth, glowRadius, glowIntensity, fillEffect, fillEffectSpd)
                    : buildPlain(dilateH, fillAlpha, lineWidth, fillEffect, fillEffectSpd);

            return PostChain.load(config, Minecraft.getInstance().getTextureManager(),
                    LevelTargetBundle.MAIN_TARGETS, CHAIN_NAME, projection);
        } catch (Exception e) {
            return null;
        }
    }

    private static PostChainConfig buildPlain(PostChainConfig.Pass dilateH, float fillAlpha,
                                              int lineWidth, int fillEffect, float fillEffectSpd) {
        List<UniformValue> outlineConfig = List.of(
                flt(fillAlpha),
                flt(1.0f),
                integer(lineWidth),
                integer(fillEffect),
                flt(fillEffectSpd)
        );
        PostChainConfig.Pass outline = new PostChainConfig.Pass(
                SCREENQUAD, OUTLINE_FSH,
                List.of(new PostChainConfig.TargetInput("In", DILATED, false, false),
                        new PostChainConfig.TargetInput("Orig", PostChain.MAIN_TARGET_ID, false, false),
                        new PostChainConfig.TextureInput("Logo", Identifier.fromNamespaceAndPath("swedenhack", "logo"), 128, 128, true)),
                PostChain.MAIN_TARGET_ID,
                Map.of("OutlineConfig", outlineConfig));

        return new PostChainConfig(
                Map.of(DILATED, new PostChainConfig.InternalTarget(Optional.empty(), Optional.empty(), false, 0)),
                List.of(dilateH, outline));
    }

    private static PostChainConfig buildGlow(PostChainConfig.Pass dilateH, float fillAlpha,
                                             int lineWidth, int glowRadius, float glowIntensity,
                                             int fillEffect, float fillEffectSpd) {
        PostChainConfig.Pass glowH = new PostChainConfig.Pass(
                SCREENQUAD, GLOW_H_FSH,
                List.of(new PostChainConfig.TargetInput("In", PostChain.MAIN_TARGET_ID, false, false)),
                GLOW_H,
                Map.of("GlowConfig", List.<UniformValue>of(integer(glowRadius))));

        List<UniformValue> outlineConfig = List.of(
                flt(fillAlpha),
                flt(1.0f),
                flt(glowIntensity),
                integer(lineWidth),
                integer(glowRadius),
                integer(fillEffect),
                flt(fillEffectSpd)
        );
        PostChainConfig.Pass outline = new PostChainConfig.Pass(
                SCREENQUAD, OUTLINE_GLOW_FSH,
                List.of(new PostChainConfig.TargetInput("In", DILATED, false, false),
                        new PostChainConfig.TargetInput("Glow", GLOW_H, false, false),
                        new PostChainConfig.TargetInput("Orig", PostChain.MAIN_TARGET_ID, false, false),
                        new PostChainConfig.TextureInput("Logo", Identifier.fromNamespaceAndPath("swedenhack", "logo"), 128, 128, true)),
                PostChain.MAIN_TARGET_ID,
                Map.of("OutlineConfig", outlineConfig));

        return new PostChainConfig(
                Map.of(DILATED, new PostChainConfig.InternalTarget(Optional.empty(), Optional.empty(), false, 0),
                       GLOW_H,  new PostChainConfig.InternalTarget(Optional.empty(), Optional.empty(), false, 0)),
                List.of(dilateH, glowH, outline));
    }

    private static UniformValue flt(float v)  { return new UniformValue.FloatUniform(v); }
    private static UniformValue integer(int v) { return new UniformValue.IntUniform(v); }
}
