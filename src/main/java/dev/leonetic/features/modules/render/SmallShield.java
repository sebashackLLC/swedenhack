package dev.leonetic.features.modules.render;

import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;

public class SmallShield extends Module {

    private static SmallShield INSTANCE;

    public final Setting<Boolean> normal = bool("Normal", false);
    public final Setting<Float> size = num("Size", 0.5f, 0.0f, 1.0f);

    public final Setting<Float> mainX = num("Main X", 0.0f, -2.0f, 2.0f);
    public final Setting<Float> mainY = num("Main Y", 0.0f, -2.0f, 2.0f);
    public final Setting<Float> mainZ = num("Main Z", 0.0f, -2.0f, 2.0f);

    public final Setting<Float> offX = num("Off X", 0.0f, -2.0f, 2.0f);
    public final Setting<Float> offY = num("Off Y", 0.0f, -2.0f, 2.0f);
    public final Setting<Float> offZ = num("Off Z", 0.0f, -2.0f, 2.0f);

    public SmallShield() {
        super("SmallShield", "Modifies the visual scale and offsets of the first-person shield.", Category.RENDER);
        INSTANCE = this;
    }

    public static SmallShield getInstance() {
        if (dev.leonetic.Swedenhack.moduleManager == null) return null;
        return dev.leonetic.Swedenhack.moduleManager.getModuleByClass(SmallShield.class);
    }

    public static SmallShield get() {
        return INSTANCE;
    }
}
