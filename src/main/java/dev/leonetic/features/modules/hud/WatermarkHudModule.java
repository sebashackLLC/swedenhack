package dev.leonetic.features.modules.hud;

import dev.leonetic.Swedenhack;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.client.HudClientModule;
import dev.leonetic.features.modules.client.HudModule;
import net.minecraft.client.gui.GuiGraphics;

public class WatermarkHudModule extends HudModule {
    private static final int WHITE = 0xFFFFFFFF;

    public WatermarkHudModule() {
        super("Watermark");
    }

    @Override
    public void render(Render2DEvent event) {
        GuiGraphics ctx = event.getContext();
        HudClientModule hudClient = Swedenhack.moduleManager.getModuleByClass(HudClientModule.class);
        int radarColor = hudClient != null
                ? hudClient.radarSelfColor.getValue().getRGB()
                : 0xFFFFFFFF;

        // Renders at top left (2, 2)
        ctx.drawString(mc.font, "Swedenhack 1.1", 2, 2, radarColor);
    }
}
