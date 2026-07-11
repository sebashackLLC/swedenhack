package dev.leonetic.features.modules.render;

import dev.leonetic.Swedenhack;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.Projectile;

import java.awt.Color;

public class ShadersModule extends Module {
    public enum Mode { Default, Off }
    public enum FillEffect { Flat, Pulse, Rainbow, Fire, Plasma, Matrix, Glitch, Scan, Sweden }

    public Setting<Mode> mode = mode("Mode", Mode.Default).setPage("General");

    public Setting<Boolean> glow          = bool("Glow", true).setPage("General");
    public Setting<Float>   glowRadius    = num("GlowRadius",    3f,    0f, 16f).setPage("General");
    public Setting<Float>   glowIntensity = num("GlowIntensity", 1.55f, 0f, 3f).setPage("General");

    public Setting<Float>   fillAlpha     = num("FillAlpha", 0.4f, 0f, 1f).setPage("General");
    public Setting<Float>   fillTint      = num("FillTint",  1.0f, 0f, 2f).setPage("General");

    // Fill effect mode
    public Setting<FillEffect> fillEffect = mode("FillEffect", FillEffect.Flat).setPage("General");
    public Setting<Float>   fillEffectSpeed = num("FillEffectSpeed", 1.0f, 0.1f, 5.0f).setPage("General");

    // Outline thickness (was hardcoded to 2)
    public Setting<Float>   outlineThickness = num("OutlineThickness", 2f, 1f, 8f).setPage("General");

    // Rainbow: animates the outline hue over time instead of using the entity's static colour
    public Setting<Boolean> rainbow       = bool("Rainbow", false).setPage("General");
    public Setting<Float>   rainbowSpeed  = num("RainbowSpeed", 1.0f, 0.1f, 5.0f).setPage("General");
    public Setting<Float>   rainbowSat    = num("RainbowSaturation", 0.9f, 0.0f, 1.0f).setPage("General");

    // Pulse: outline brightness breathes in and out
    public Setting<Boolean> pulse         = bool("Pulse", false).setPage("General");
    public Setting<Float>   pulseSpeed    = num("PulseSpeed", 2.0f, 0.1f, 10.0f).setPage("General");
    public Setting<Float>   pulseStrength = num("PulseStrength", 0.35f, 0.0f, 1.0f).setPage("General");

    // Distance fade: outline alpha drops off past a configurable range
    public Setting<Boolean> distFade      = bool("DistanceFade", false).setPage("General");
    public Setting<Float>   distFadeStart = num("FadeStart", 16f, 1f, 128f).setPage("General");
    public Setting<Float>   distFadeEnd   = num("FadeEnd",   48f, 1f, 256f).setPage("General");

    public Setting<Boolean> players     = bool("Players",     true).setPage("Entities");
    public Setting<Float>   playerRange  = num("PlayerRange",     64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> friends     = bool("Friends",     true).setPage("Entities");
    public Setting<Float>   friendRange  = num("FriendRange",     64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> monsters    = bool("Monsters",    true).setPage("Entities");
    public Setting<Float>   monsterRange = num("MonsterRange",    64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> animals     = bool("Animals",     false).setPage("Entities");
    public Setting<Float>   animalRange  = num("AnimalRange",     64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> items       = bool("Items",       false).setPage("Entities");
    public Setting<Float>   itemRange    = num("ItemRange",       64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> crystals    = bool("Crystals",    true).setPage("Entities");
    public Setting<Float>   crystalRange = num("CrystalRange",    64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> projectiles = bool("Projectiles", false).setPage("Entities");
    public Setting<Float>   projectileRange = num("ProjectileRange", 64f, 4f, 256f).setPage("Entities");

    public Setting<Boolean> syncColor     = bool("SyncColor", false).setPage("Colors");
    public Setting<Color> playerColor     = color("PlayerOutline",     255, 0,   0,   255).setPage("Colors").setVisibility(v -> !syncColor.getValue());
    public Setting<Color> friendColor     = color("FriendOutline",     0,   255, 100, 255).setPage("Colors").setVisibility(v -> !syncColor.getValue());
    public Setting<Color> monsterColor    = color("MonsterOutline",    200, 60,  60,  255).setPage("Colors").setVisibility(v -> !syncColor.getValue());
    public Setting<Color> animalColor     = color("AnimalOutline",     255, 200, 60,  255).setPage("Colors").setVisibility(v -> !syncColor.getValue());
    public Setting<Color> itemColor       = color("ItemOutline",       255, 255, 255, 255).setPage("Colors").setVisibility(v -> !syncColor.getValue());
    public Setting<Color> crystalColor    = color("CrystalOutline",    0,   200, 255, 255).setPage("Colors").setVisibility(v -> !syncColor.getValue());
    public Setting<Color> projectileColor = color("ProjectileOutline", 200, 200, 0,   255).setPage("Colors").setVisibility(v -> !syncColor.getValue());

    public Setting<Boolean> hand          = bool("HandShaders", false).setPage("Hand");
    public Setting<Boolean> handOutline   = bool("HandOutline", true).setPage("Hand");
    public Setting<Float>   handThickness = num("HandThickness", 2f, 1f, 10f).setPage("Hand");
    public Setting<Boolean> handFill      = bool("HandFill", false).setPage("Hand");

    // Hand fill effect — mirrors the entity FillEffect enum
    public Setting<FillEffect> handFillEffect      = mode("HandFillEffect", FillEffect.Flat).setPage("Hand");
    public Setting<Float>      handFillEffectSpeed = num("HandFillEffectSpeed", 1.0f, 0.1f, 5.0f).setPage("Hand");
    public Setting<Float>      handFillAlpha       = num("HandFillAlpha", 0.35f, 0.0f, 1.0f).setPage("Hand");

    public Setting<Boolean> handGlow          = bool("HandGlow", false).setPage("Hand");
    public Setting<Float>   handGlowRadius    = num("HandGlowRadius",    4f, 1f, 16f).setPage("Hand");
    public Setting<Float>   handGlowIntensity = num("HandGlowIntensity", 1f, 0f, 3f).setPage("Hand");

    public Setting<Color> handColor = color("HandColor", 255, 0, 0, 255).setPage("Hand");

    public ShadersModule() {
        super("Shaders", "Stylised post-effect shader on selected entities", Category.RENDER);
        glow.setVisibility(v -> mode.getValue() == Mode.Default);
        glowRadius.setVisibility(v -> mode.getValue() == Mode.Default && glow.getValue());
        glowIntensity.setVisibility(v -> mode.getValue() == Mode.Default && glow.getValue());

        outlineThickness.setVisibility(v -> mode.getValue() == Mode.Default);
        rainbow.setVisibility(v -> mode.getValue() == Mode.Default);
        rainbowSpeed.setVisibility(v -> mode.getValue() == Mode.Default && rainbow.getValue());
        rainbowSat.setVisibility(v -> mode.getValue() == Mode.Default && rainbow.getValue());
        pulse.setVisibility(v -> mode.getValue() == Mode.Default);
        pulseSpeed.setVisibility(v -> mode.getValue() == Mode.Default && pulse.getValue());
        pulseStrength.setVisibility(v -> mode.getValue() == Mode.Default && pulse.getValue());
        distFade.setVisibility(v -> mode.getValue() == Mode.Default);
        distFadeStart.setVisibility(v -> mode.getValue() == Mode.Default && distFade.getValue());
        distFadeEnd.setVisibility(v -> mode.getValue() == Mode.Default && distFade.getValue());

        fillEffect.setVisibility(v -> mode.getValue() == Mode.Default);
        fillEffectSpeed.setVisibility(v -> mode.getValue() == Mode.Default
                && fillEffect.getValue() != FillEffect.Flat);

        playerRange.setVisibility(v -> players.getValue());
        friendRange.setVisibility(v -> friends.getValue());
        monsterRange.setVisibility(v -> monsters.getValue());
        animalRange.setVisibility(v -> animals.getValue());
        itemRange.setVisibility(v -> items.getValue());
        crystalRange.setVisibility(v -> crystals.getValue());
        projectileRange.setVisibility(v -> projectiles.getValue());

        handThickness.setVisibility(v -> hand.getValue() && handOutline.getValue());
        handOutline.setVisibility(v -> hand.getValue());
        handFill.setVisibility(v -> hand.getValue());
        handFillEffect.setVisibility(v -> hand.getValue() && handFill.getValue());
        handFillEffectSpeed.setVisibility(v -> hand.getValue() && handFill.getValue()
                && handFillEffect.getValue() != FillEffect.Flat);
        handFillAlpha.setVisibility(v -> hand.getValue() && handFill.getValue());
        handGlow.setVisibility(v -> hand.getValue());
        handGlowRadius.setVisibility(v -> hand.getValue() && handGlow.getValue());
        handGlowIntensity.setVisibility(v -> hand.getValue() && handGlow.getValue());
        handColor.setVisibility(v -> hand.getValue());
    }

    public boolean wantsHandShader() {
        return isEnabled() && hand.getValue();
    }

    public int getHandThickness() {
        return Math.round(handThickness.getValue());
    }

    public int getHandGlowRadius() {
        return handGlow.getValue() ? Math.round(handGlowRadius.getValue()) : 0;
    }

    public float getHandGlowIntensity() {
        return handGlowIntensity.getValue();
    }

    public int getHandRgb() {
        Color c = handColor.getValue();
        return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    public int getHandFillEffectOrdinal() {
        FillEffect fe = handFillEffect.getValue();
        return fe != null ? fe.ordinal() : 0;
    }

    public float getHandFillEffectSpeed() {
        return handFillEffectSpeed.getValue();
    }

    public float getHandFillAlpha() {
        return handFill.getValue() ? handFillAlpha.getValue() : 0.0f;
    }

    public int getGlowRadius() {
        return glow.getValue() ? Math.round(glowRadius.getValue()) : 0;
    }

    public float getGlowIntensity() {
        return glowIntensity.getValue();
    }

    public float getFillTint() {
        return fillTint.getValue();
    }

    public float getFillAlpha() {
        return fillAlpha.getValue();
    }

    public int getFillEffectOrdinal() {
        FillEffect fe = fillEffect.getValue();
        return fe != null ? fe.ordinal() : 0;
    }

    public float getFillEffectSpeed() {
        return fillEffectSpeed.getValue();
    }

    public int getOutlineThickness() {
        return Math.max(1, Math.round(outlineThickness.getValue()));
    }

    public boolean isRainbow() {
        return rainbow.getValue();
    }

    public float getRainbowSpeed() {
        return rainbowSpeed.getValue();
    }

    public float getRainbowSaturation() {
        return rainbowSat.getValue();
    }

    public boolean isPulse() {
        return pulse.getValue();
    }

    public float getPulseSpeed() {
        return pulseSpeed.getValue();
    }

    public float getPulseStrength() {
        return pulseStrength.getValue();
    }

    public boolean isDistFade() {
        return distFade.getValue();
    }

    public float getDistFadeStart() {
        return distFadeStart.getValue();
    }

    public float getDistFadeEnd() {
        return distFadeEnd.getValue();
    }

    public boolean wantsOutlines() {
        Mode m = mode.getValue();
        return m != null && m != Mode.Off;
    }

    public boolean shouldShader(Entity entity) {
        Mode m = mode.getValue();
        if (m == null || m == Mode.Off) return false;
        if (entity == null) return false;
        if (mc.player != null && entity == mc.player) return false;

        if (entity instanceof AbstractClientPlayer p && !(entity instanceof LocalPlayer)) {
            boolean friend = Swedenhack.friendManager.isFriend(p);
            return friend
                    ? friends.getValue() && inRange(entity, friendRange.getValue())
                    : players.getValue() && inRange(entity, playerRange.getValue());
        }
        if (entity instanceof Monster)     return monsters.getValue()    && inRange(entity, monsterRange.getValue());
        if (entity instanceof Animal)      return animals.getValue()     && inRange(entity, animalRange.getValue());
        if (entity instanceof ItemEntity)  return items.getValue()       && inRange(entity, itemRange.getValue());
        if (entity instanceof EndCrystal)  return crystals.getValue()    && inRange(entity, crystalRange.getValue());
        if (entity instanceof Projectile)  return projectiles.getValue() && inRange(entity, projectileRange.getValue());
        return false;
    }

    private boolean inRange(Entity entity, float range) {
        return mc.player == null || entity.distanceTo(mc.player) <= range;
    }

    public int getRgbFor(Entity entity) {
        Color c = colorFor(entity);
        return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    private Color colorFor(Entity entity) {
        if (syncColor.getValue()) {
            return Swedenhack.colorManager.get("ui");
        }
        if (entity instanceof AbstractClientPlayer p && !(entity instanceof LocalPlayer)) {
            return Swedenhack.friendManager.isFriend(p) ? friendColor.getValue() : playerColor.getValue();
        }
        if (entity instanceof Monster) return monsterColor.getValue();
        if (entity instanceof Animal) return animalColor.getValue();
        if (entity instanceof ItemEntity) return itemColor.getValue();
        if (entity instanceof EndCrystal) return crystalColor.getValue();
        if (entity instanceof Projectile) return projectileColor.getValue();
        return playerColor.getValue();
    }
}
