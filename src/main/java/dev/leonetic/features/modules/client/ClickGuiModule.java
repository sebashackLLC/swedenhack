package dev.leonetic.features.modules.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.leonetic.Swedenhack;
import dev.leonetic.event.impl.ClientEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.commands.Command;
import dev.leonetic.features.gui.SwedenhackGui;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Bind;
import dev.leonetic.features.settings.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

public class ClickGuiModule extends Module {
    private static ClickGuiModule INSTANCE;

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("swedenhack", "swedenhack"));

    public static final KeyMapping OPEN_GUI_KEY = new KeyMapping(
            "key.swedenhack.open_gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_SHIFT,
            CATEGORY
    );

    public Setting<String> prefix = str("Prefix", ".");
    public Setting<Theme> theme = mode("Theme", Theme.SWEDENHACK);
    public Setting<Color> customColor = color("Custom Color", 120, 170, 210, 220);
    public Setting<Color> gradientStart = color("Gradient Start", 140, 26, 38, 220);
    public Setting<Color> gradientEnd = color("Gradient End", 190, 72, 82, 220);
    public Setting<Float> gradientSpeed = num("Gradient Speed", 1.0f, 0.1f, 5.0f);
    public Setting<Boolean> smooth = bool("Smooth", true);
    public Setting<Integer> rainbowHue = num("Delay", 240, 0, 600);
    public Setting<Float> rainbowBrightness = num("Brightness", 200.0f, 1.0f, 255.0f);
    public Setting<Float> rainbowSaturation = num("Saturation", 140.0f, 1.0f, 255.0f);

    // Menu background effect
    public Setting<MenuEffect> menuEffect      = mode("MenuEffect", MenuEffect.NONE);
    public Setting<Float>      menuEffectAlpha = num("MenuEffectAlpha", 0.55f, 0.05f, 1.0f);
    public Setting<Float>      menuEffectSpeed = num("MenuEffectSpeed", 1.0f, 0.1f, 4.0f);

    public Setting<Boolean> clickGuiFont = bool("ClickGUI Font", false);
    public Setting<Boolean> hudFont = bool("HUD Font", false);
    public Setting<String> fontName = str("Font Name", "");
    public Setting<Float> fontScale = num("Font Scale", 1.0f, 0.5f, 2.0f);

    private static final Color CAT_COMBAT   = new Color(196,  88,  90);
    private static final Color CAT_WORLD    = new Color(118, 168, 118);
    private static final Color CAT_RENDER   = new Color( 98, 166, 200);
    private static final Color CAT_MOVEMENT = new Color(212, 142,  78);
    private static final Color CAT_PLAYER   = new Color(202, 180,  92);
    private static final Color CAT_FUNNY    = new Color(198, 124, 178);
    private static final Color CAT_CLIENT   = new Color( 70,  75,  82);
    private static final Color CAT_HUD      = new Color( 96, 142, 200);

    private static final Color SWEDENHACK_ACCENT = new Color(140, 26, 38);
    private static final Color SWEDENHACK_MODULE = new Color(190, 72, 82);

    public Color categoryAccent(Module.Category cat) {
        return categoryAccent(cat, 0f);
    }

    public Color categoryAccent(Module.Category cat, float yOffset) {
        Theme t = theme.getValue();
        if (t == Theme.RAINBOW) return rainbowAt(yOffset);
        if (t == Theme.SWEDEN) return swedenAt(yOffset);
        if (t == Theme.CUSTOM) return customColor.getValue();
        if (t == Theme.GRADIENT) return gradientAt(yOffset);
        if (t == Theme.SWEDENHACK) return SWEDENHACK_ACCENT;
        if (cat == null) return CAT_CLIENT;
        switch (cat) {
            case COMBAT:   return CAT_COMBAT;
            case WORLD:    return CAT_WORLD;
            case RENDER:   return CAT_RENDER;
            case MOVEMENT: return CAT_MOVEMENT;
            case PLAYER:   return CAT_PLAYER;
            case FUNNY:    return CAT_FUNNY;
            case CLIENT:   return CAT_CLIENT;
            case HUD:      return CAT_HUD;
            default:       return CAT_CLIENT;
        }
    }

    public Color moduleAccent(Module.Category cat, float yOffset) {
        if (theme.getValue() == Theme.SWEDENHACK) return SWEDENHACK_MODULE;
        return categoryAccent(cat, yOffset);
    }

    public Color chatAccent() {
        Theme t = theme.getValue();
        if (t == Theme.RAINBOW) return rainbowAt(0f);
        if (t == Theme.SWEDEN) return swedenAt(0f);
        if (t == Theme.CUSTOM) return customColor.getValue();
        if (t == Theme.GRADIENT) return gradientStart.getValue();
        if (t == Theme.SWEDENHACK) return SWEDENHACK_ACCENT;
        return Color.WHITE;
    }

    public Color rainbowAt(float yOffset) {
        return dev.leonetic.util.ColorUtil.rainbow((int) (yOffset / 10f * rainbowHue.getValue()));
    }

    public Color swedenAt(float yOffset) {
        return dev.leonetic.util.ColorUtil.sweden((int) (yOffset / 10f * rainbowHue.getValue()));
    }

    public Color gradientAt(float yOffset) {
        Color start = gradientStart.getValue();
        Color end = gradientEnd.getValue();
        float speed = gradientSpeed.getValue();
        long time = System.currentTimeMillis();
        float t = ((time / 500.0f * speed + yOffset / 150.0f) % 1.0f);
        int r = (int) (start.getRed() + (end.getRed() - start.getRed()) * t);
        int g = (int) (start.getGreen() + (end.getGreen() - start.getGreen()) * t);
        int b = (int) (start.getBlue() + (end.getBlue() - start.getBlue()) * t);
        int a = (int) (start.getAlpha() + (end.getAlpha() - start.getAlpha()) * t);
        return new Color(r, g, b, a);
    }

    public static Color[] gradientPair(float yOffset) {
        ClickGuiModule cgm = getInstance();
        if (cgm == null) return new Color[]{new Color(140, 26, 38, 220), new Color(190, 72, 82, 220)};
        Color start = cgm.gradientStart.getValue();
        Color end = cgm.gradientEnd.getValue();
        float speed = cgm.gradientSpeed.getValue();
        long time = System.currentTimeMillis();
        float pos = ((time / 250.0f * speed + yOffset / 150.0f) % 4.0f);
        float t = pos <= 2.0f ? pos / 2.0f : (4.0f - pos) / 2.0f;
        float t2 = (t + 0.5f) % 1.0f;
        Color left = new Color(
            (int)(start.getRed() + (end.getRed() - start.getRed()) * t),
            (int)(start.getGreen() + (end.getGreen() - start.getGreen()) * t),
            (int)(start.getBlue() + (end.getBlue() - start.getBlue()) * t),
            (int)(start.getAlpha() + (end.getAlpha() - start.getAlpha()) * t)
        );
        Color right = new Color(
            (int)(start.getRed() + (end.getRed() - start.getRed()) * t2),
            (int)(start.getGreen() + (end.getGreen() - start.getGreen()) * t2),
            (int)(start.getBlue() + (end.getBlue() - start.getBlue()) * t2),
            (int)(start.getAlpha() + (end.getAlpha() - start.getAlpha()) * t2)
        );
        return new Color[]{left, right};
    }

    public float getExpandSpeed() {
        return smooth.getValue() ? 0.5f : 1f;
    }

    public MenuEffect getMenuEffect()      { return menuEffect.getValue(); }
    public float      getMenuEffectAlpha() { return menuEffectAlpha.getValue(); }
    public float      getMenuEffectSpeed() { return menuEffectSpeed.getValue(); }

    public ClickGuiModule() {
        super("ClickGui", "Opens the ClickGui", Module.Category.CLIENT);

        this.bind.setValue(new Bind(GLFW.GLFW_KEY_UNKNOWN));
        this.bind.setVisibility(v -> false);
        this.bindMode.setVisibility(v -> false);
        KeyBindingHelper.registerKeyBinding(OPEN_GUI_KEY);
        rainbowHue.setVisibility(v -> theme.getValue() == Theme.RAINBOW || theme.getValue() == Theme.SWEDEN);
        rainbowBrightness.setVisibility(v -> theme.getValue() == Theme.RAINBOW);
        rainbowSaturation.setVisibility(v -> theme.getValue() == Theme.RAINBOW);
        customColor.setVisibility(v -> theme.getValue() == Theme.CUSTOM);
        gradientStart.setVisibility(v -> theme.getValue() == Theme.GRADIENT);
        gradientEnd.setVisibility(v -> theme.getValue() == Theme.GRADIENT);
        gradientSpeed.setVisibility(v -> theme.getValue() == Theme.GRADIENT);
        fontName.setVisibility(v -> clickGuiFont.getValue() || hudFont.getValue());
        fontScale.setVisibility(v -> clickGuiFont.getValue() || hudFont.getValue());
        menuEffectAlpha.setVisibility(v -> menuEffect.getValue() != MenuEffect.NONE);
        menuEffectSpeed.setVisibility(v -> menuEffect.getValue() != MenuEffect.NONE
                && menuEffect.getValue() != MenuEffect.VIGNETTE);
        INSTANCE = this;
    }

    @Subscribe
    public void onSettingChange(ClientEvent event) {
        if (event.getType() == ClientEvent.Type.SETTING_UPDATE && event.getSetting().getFeature().equals(this)) {
            if (event.getSetting().equals(this.prefix)) {
                Swedenhack.commandManager.setCommandPrefix(this.prefix.getPlannedValue());
                Command.sendMessage("Prefix set to {global} %s", Swedenhack.commandManager.getCommandPrefix());
            }
            if (event.getSetting().equals(this.clickGuiFont)
                    || event.getSetting().equals(this.hudFont)
                    || event.getSetting().equals(this.fontName)
                    || event.getSetting().equals(this.fontScale)) {
                dev.leonetic.util.render.font.Fonts.markDirty();
            }
        }
    }

    @Override
    public void onEnable() {
        if (nullCheck()) {
            return;
        }
        mc.setScreen(SwedenhackGui.getClickGui());
    }

    public Color uiAccent() {
        Theme t = theme.getValue();
        if (t == Theme.RAINBOW) return rainbowAt(0f);
        if (t == Theme.SWEDEN) return swedenAt(0f);
        if (t == Theme.CUSTOM) return customColor.getValue();
        if (t == Theme.GRADIENT) return gradientStart.getValue();
        if (t == Theme.SWEDENHACK) return SWEDENHACK_ACCENT;
        return CAT_RENDER;
    }

    @Override
    public void onLoad() {
        Swedenhack.colorManager.register("ui", this::uiAccent);
        Swedenhack.colorManager.register("chat", this::chatAccent);
        Swedenhack.colorManager.register("chatBracket", this::chatAccent);
        Swedenhack.commandManager.setCommandPrefix(this.prefix.getValue());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_GUI_KEY.consumeClick()) {
                if (client.screen == null && !isEnabled()) enable();
            }
        });
    }

    @Override
    public void onTick() {
        if (!(ClickGuiModule.mc.screen instanceof SwedenhackGui)) {
            this.disable();
        }
    }

    public static ClickGuiModule getInstance() {
        return INSTANCE;
    }

    public enum Theme {
        SWEDENHACK, COLORS, CUSTOM, RAINBOW, SWEDEN, GRADIENT
    }

    public enum MenuEffect {
        NONE, GRADIENT, RAINBOW, PLASMA, MATRIX, VIGNETTE, SCANLINES
    }
}
