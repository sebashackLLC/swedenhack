package dev.leonetic.util.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.resources.Identifier;
import dev.leonetic.features.modules.render.ShadersModule;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class WorldChamsChain {

    private static final Identifier SCREENQUAD = Identifier.fromNamespaceAndPath("minecraft", "core/screenquad");
    private static final Identifier H_FSH      = Identifier.fromNamespaceAndPath("swedenhack", "post/chams_default_h");
    private static final Identifier C_FSH      = Identifier.fromNamespaceAndPath("swedenhack", "post/chams_default_c");
    private static final Identifier V_FSH      = Identifier.fromNamespaceAndPath("swedenhack", "post/chams_default_v");
    private static final Identifier CHAMS_H    = Identifier.fromNamespaceAndPath("swedenhack", "chams_h");
    private static final Identifier CHAMS_C    = Identifier.fromNamespaceAndPath("swedenhack", "chams_c");
    private static final Identifier CHAIN_NAME = Identifier.fromNamespaceAndPath("swedenhack", "chams_default_runtime");

    private static final int LINE_WIDTH = 2; // kept for reference; overridden by get()

    private static CachedOrthoProjectionMatrixBuffer projection;
    private static PostChain cached;
    private static int   lastGlowRadius    = Integer.MIN_VALUE;
    private static float lastGlowIntensity = Float.NaN;
    private static float lastFillTint      = Float.NaN;
    private static float lastFillAlpha     = Float.NaN;
    private static int   lastLineWidth     = Integer.MIN_VALUE;
    private static float lastRainbowSpeed  = Float.NaN;
    private static float lastRainbowSat    = Float.NaN;
    private static float lastPulseSpeed    = Float.NaN;
    private static float lastPulseStrength = Float.NaN;
    private static float lastFadeStart     = Float.NaN;
    private static float lastFadeEnd       = Float.NaN;
    private static int   lastFillEffect    = Integer.MIN_VALUE;
    private static float lastFillEffectSpd = Float.NaN;

    private WorldChamsChain() {}

    public static PostChain get(Set<Identifier> externalTargets, int glowRadius, float glowIntensity,
                                float fillTint, float fillAlpha) {
        int lineWidth = LINE_WIDTH;
        float rainbowSpeed = 0f, rainbowSat = 0f, pulseSpeed = 0f, pulseStrength = 0f,
              fadeStart = 0f, fadeEnd = 0f;
        int fillEffect = 0;
        float fillEffectSpd = 1f;
        // Pull from ShadersModule if available
        ShadersModule sh = dev.leonetic.Swedenhack.moduleManager != null
                ? dev.leonetic.Swedenhack.moduleManager.getModuleByClass(ShadersModule.class) : null;
        if (sh != null) {
            lineWidth      = sh.getOutlineThickness();
            rainbowSpeed   = sh.isRainbow()   ? sh.getRainbowSpeed()      : 0f;
            rainbowSat     = sh.isRainbow()   ? sh.getRainbowSaturation() : 0f;
            pulseSpeed     = sh.isPulse()     ? sh.getPulseSpeed()        : 0f;
            pulseStrength  = sh.isPulse()     ? sh.getPulseStrength()     : 0f;
            fadeStart      = sh.isDistFade()  ? sh.getDistFadeStart()     : 0f;
            fadeEnd        = sh.isDistFade()  ? sh.getDistFadeEnd()       : 0f;
            fillEffect     = sh.getFillEffectOrdinal();
            fillEffectSpd  = sh.getFillEffectSpeed();
        }

        if (cached != null
                && glowRadius      == lastGlowRadius
                && glowIntensity   == lastGlowIntensity
                && fillTint        == lastFillTint
                && fillAlpha       == lastFillAlpha
                && lineWidth       == lastLineWidth
                && rainbowSpeed    == lastRainbowSpeed
                && rainbowSat      == lastRainbowSat
                && pulseSpeed      == lastPulseSpeed
                && pulseStrength   == lastPulseStrength
                && fadeStart       == lastFadeStart
                && fadeEnd         == lastFadeEnd
                && fillEffect      == lastFillEffect
                && fillEffectSpd   == lastFillEffectSpd) {
            return cached;
        }

        PostChain rebuilt = build(externalTargets, lineWidth, glowRadius, glowIntensity,
                fillTint, fillAlpha, rainbowSpeed, rainbowSat, pulseSpeed, pulseStrength,
                fadeStart, fadeEnd, fillEffect, fillEffectSpd);
        if (rebuilt == null) return cached;
        if (cached != null) cached.close();
        cached = rebuilt;
        lastGlowRadius    = glowRadius;
        lastGlowIntensity = glowIntensity;
        lastFillTint      = fillTint;
        lastFillAlpha     = fillAlpha;
        lastLineWidth     = lineWidth;
        lastRainbowSpeed  = rainbowSpeed;
        lastRainbowSat    = rainbowSat;
        lastPulseSpeed    = pulseSpeed;
        lastPulseStrength = pulseStrength;
        lastFadeStart     = fadeStart;
        lastFadeEnd       = fadeEnd;
        lastFillEffect    = fillEffect;
        lastFillEffectSpd = fillEffectSpd;
        return cached;
    }

    private static PostChain build(Set<Identifier> externalTargets, int lineWidth,
                                   int glowThickness, float glowIntensity,
                                   float fillTint, float fillAlpha,
                                   float rainbowSpeed, float rainbowSat,
                                   float pulseSpeed, float pulseStrength,
                                   float fadeStart, float fadeEnd,
                                   int fillEffect, float fillEffectSpd) {
        try {
            if (projection == null) {
                projection = new CachedOrthoProjectionMatrixBuffer("swedenhack_chams", 0.1f, 1000.0f, false);
            }

            Identifier outlineTarget = LevelTargetBundle.ENTITY_OUTLINE_TARGET_ID;

            PostChainConfig.Pass passH = new PostChainConfig.Pass(
                    SCREENQUAD, H_FSH,
                    List.of(new PostChainConfig.TargetInput("In", outlineTarget, false, false)),
                    CHAMS_H,
                    Map.of("DilateConfig", List.<UniformValue>of(integer(lineWidth), integer(glowThickness))));

            PostChainConfig.Pass passC = new PostChainConfig.Pass(
                    SCREENQUAD, C_FSH,
                    List.of(new PostChainConfig.TargetInput("In", outlineTarget, false, false)),
                    CHAMS_C,
                    Map.of("ColorConfig", List.<UniformValue>of(integer(lineWidth), integer(glowThickness))));

            List<UniformValue> chamsConfig = List.of(
                    flt(glowIntensity),
                    flt(fillTint),
                    flt(fillAlpha),
                    integer(glowThickness),
                    integer(lineWidth),
                    flt(rainbowSpeed),
                    flt(rainbowSat),
                    flt(pulseSpeed),
                    flt(pulseStrength),
                    flt(fadeStart),
                    flt(fadeEnd),
                    integer(fillEffect),
                    flt(fillEffectSpd)
            );
            PostChainConfig.Pass passV = new PostChainConfig.Pass(
                    SCREENQUAD, V_FSH,
                    List.of(new PostChainConfig.TargetInput("In", CHAMS_H, false, false),
                            new PostChainConfig.TargetInput("Color", CHAMS_C, false, false),
                            new PostChainConfig.TargetInput("Orig", outlineTarget, false, false),
                            new PostChainConfig.TextureInput("Logo", Identifier.fromNamespaceAndPath("swedenhack", "logo"), 128, 128, true)),
                    outlineTarget,
                    Map.of("ChamsConfig", chamsConfig));

            PostChainConfig config = new PostChainConfig(
                    Map.of(CHAMS_H, new PostChainConfig.InternalTarget(Optional.empty(), Optional.empty(), false, 0),
                           CHAMS_C, new PostChainConfig.InternalTarget(Optional.empty(), Optional.empty(), false, 0)),
                    List.of(passH, passC, passV));

            return PostChain.load(config, Minecraft.getInstance().getTextureManager(),
                    externalTargets, CHAIN_NAME, projection);
        } catch (Exception e) {
            return null;
        }
    }

    private static UniformValue flt(float v) {
        return new UniformValue.FloatUniform(v);
    }

    private static UniformValue integer(int v) {
        return new UniformValue.IntUniform(v);
    }
}
