package dev.leonetic.features.gui.items.buttons;

import dev.leonetic.features.gui.GuiTheme;
import dev.leonetic.features.gui.Widget;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.awt.Color;

public class BooleanButton extends SettingButton<Boolean> {
    public BooleanButton(Setting<Boolean> setting) {
        super(setting);
        this.height = GuiTheme.SETTING_HEIGHT;
    }

    @Override
    public int getHeight() { return GuiTheme.SETTING_HEIGHT; }

    @Override
    public void drawScreen(GuiGraphics context, int mouseX, int mouseY, float partialTicks) {
        boolean hovering = this.isHovering(mouseX, mouseY);
        Color accent = Widget.currentAccent != null ? Widget.currentAccent : new Color(120, 175, 220);
        
        if (this.getState()) {
            int color = hovering ? GuiTheme.withAlpha(accent, 55).getRGB() : accent.getRGB();
            RenderUtil.rect(context, this.x, this.y, this.x + this.width, this.y + this.height, color);
        } else if (hovering) {
            RenderUtil.rect(context, this.x, this.y, this.x + this.width, this.y + this.height, 0x77AAAAAB);
        }

        int textColor = this.getState() ? GuiTheme.TEXT_MODULE_ON : GuiTheme.TEXT_SETTING;
        drawScrollableString(this.getName(), this.x + 2f,
                this.y + (this.height - 8) / 2f,
                textColor, this.width - 4, hovering);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.isHovering(mouseX, mouseY)) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
        }
    }

    @Override
    public void toggle() { this.setting.setValue(!this.setting.getValue()); }

    @Override
    public boolean getState() { return this.setting.getValue(); }
}
