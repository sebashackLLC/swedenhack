package dev.leonetic.features.modules.render;

import dev.leonetic.Swedenhack;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.mixin.client.ClientLevelWeatherAccessor;
import dev.leonetic.mixin.client.OptionInstanceAccessor;

import java.awt.Color;
import java.util.function.Predicate;

public class WorldVisualsModule extends Module {

    // ── Fog ──────────────────────────────────────────────────────────────────
    public final Setting<Boolean> customFog  = bool("CustomFog",  false).setPage("Fog");
    public final Setting<Color>   fogColor   = color("FogColor",  100, 160, 220, 255).setPage("Fog");
    /** 0–1 as fraction of render distance where fog starts. */
    public final Setting<Float>   fogStart   = num("FogStart",   0.1f, 0.0f, 1.0f).setPage("Fog");
    /** 0–1 as fraction of render distance where fog is fully opaque. */
    public final Setting<Float>   fogEnd     = num("FogEnd",     0.8f, 0.1f, 1.0f).setPage("Fog");

    // ── Brightness ───────────────────────────────────────────────────────────
    public final Setting<Boolean> customGamma = bool("CustomGamma", false).setPage("Brightness");
    public final Setting<Float>   gamma       = num("Gamma",        5.0f, 0.0f, 16.0f).setPage("Brightness");

    // ── Time ─────────────────────────────────────────────────────────────────
    public final Setting<Boolean> lockTime  = bool("LockTime",  false).setPage("Time");
    public final Setting<Integer> timeOfDay = num("TimeOfDay",  6000, 0, 24000).setPage("Time");

    // ── Weather ──────────────────────────────────────────────────────────────
    public final Setting<Boolean> noRain    = bool("NoRain",    false).setPage("Weather");
    public final Setting<Boolean> noThunder = bool("NoThunder", false).setPage("Weather");

    // ── Sky ───────────────────────────────────────────────────────────────────
    public final Setting<Boolean> customSkyColor = bool("CustomSkyColor", false).setPage("Sky");
    public final Setting<Color>   skyColor       = color("SkyColor",      0, 100, 200, 255).setPage("Sky");
    public final Setting<Boolean> customVoidColor = bool("CustomVoid",    false).setPage("Sky");
    public final Setting<Color>   voidColor       = color("VoidColor",    0, 0, 0, 255).setPage("Sky");

    private double savedGamma = 1.0;

    public WorldVisualsModule() {
        super("WorldVisuals", "Customize fog, brightness, time, weather and sky.", Category.RENDER);
        fogColor.setVisibility(v -> customFog.getValue());
        fogStart.setVisibility(v -> customFog.getValue());
        fogEnd.setVisibility(v -> customFog.getValue());
        gamma.setVisibility(v -> customGamma.getValue());
        timeOfDay.setVisibility(v -> lockTime.getValue());
        skyColor.setVisibility(v -> customSkyColor.getValue());
        voidColor.setVisibility(v -> customVoidColor.getValue());
    }

    @Override
    public void onEnable() {
        savedGamma = mc.options.gamma().get();
    }

    @Override
    public void onDisable() {
        if (customGamma.getValue()) setGamma(savedGamma);
    }

    @Override
    public void onTick() {
        if (nullCheck()) return;

        if (customGamma.getValue()) setGamma(gamma.getValue());

        if (mc.level != null) {
            if (lockTime.getValue()) {
                setClientTime(timeOfDay.getValue());
            }
            if (noRain.getValue()) {
                ClientLevelWeatherAccessor w = (ClientLevelWeatherAccessor)(Object) mc.level;
                w.swedenhack$setRainLevel(0f);
                w.swedenhack$setORainLevel(0f);
            }
            if (noThunder.getValue()) {
                ClientLevelWeatherAccessor w = (ClientLevelWeatherAccessor)(Object) mc.level;
                w.swedenhack$setThunderLevel(0f);
                w.swedenhack$setOThunderLevel(0f);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void setGamma(double value) {
        ((OptionInstanceAccessor<Double>) (Object) mc.options.gamma()).swedenhack$setValue(value);
    }

    // Cached reflection field for Level.dayTime
    private static java.lang.reflect.Field dayTimeField = null;

    private void setClientTime(long time) {
        if (mc.level == null) return;
        try {
            if (dayTimeField == null) {
                Class<?> cls = mc.level.getClass();
                while (cls != null) {
                    try {
                        dayTimeField = cls.getDeclaredField("dayTime");
                        dayTimeField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException e) {
                        cls = cls.getSuperclass();
                    }
                }
            }
            if (dayTimeField != null) dayTimeField.setLong(mc.level, time);
        } catch (Throwable ignored) {}
    }

    // ── Static helpers for mixins ────────────────────────────────────────────

    public static WorldVisualsModule getInstance() {
        if (Swedenhack.moduleManager == null) return null;
        return Swedenhack.moduleManager.getModuleByClass(WorldVisualsModule.class);
    }

    public static boolean isActive(Predicate<WorldVisualsModule> predicate) {
        WorldVisualsModule m = getInstance();
        return m != null && m.isEnabled() && predicate.test(m);
    }

    /** Inline interface — implemented by MixinClientLevelDayTime. */
    public interface ClientLevelDayTimeAccessor {
        void swedenhack$setDayTime(long time);
    }
}
