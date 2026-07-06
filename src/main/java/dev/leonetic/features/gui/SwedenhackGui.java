package dev.leonetic.features.gui;

import dev.leonetic.Swedenhack;
import dev.leonetic.features.Feature;
import dev.leonetic.features.gui.items.Item;
import dev.leonetic.features.gui.items.SearchBar;
import dev.leonetic.features.gui.items.TextBox;
import dev.leonetic.util.render.GuiFade;
import dev.leonetic.features.gui.items.buttons.ModuleButton;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.ClickGuiModule;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;

public class SwedenhackGui extends Screen {
    private static SwedenhackGui INSTANCE;
    private static Color colorClipboard = null;
    private float alpha = 0f;
    private long openTime = 0;
    private boolean closing = false;
    private long closeTime = 0;
    private static final long FADE_DURATION = 150L;



    private final ArrayList<Widget> widgets = new ArrayList<>();
    private SearchBar searchBar;

    public SwedenhackGui() {
        super(Component.literal("Swedenhack"));
        setInstance();
        load();
    }

    public static SwedenhackGui getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SwedenhackGui();
        }
        return INSTANCE;
    }

    public static SwedenhackGui getClickGui() {
        return SwedenhackGui.getInstance();
    }

    private void setInstance() {
        INSTANCE = this;
    }

    private void load() {
        int spacing = GuiTheme.PANEL_WIDTH + GuiTheme.PANEL_SPACING;
        int x = 6 - spacing;
        for (Module.Category category : Swedenhack.moduleManager.getCategories()) {
            if (category == Module.Category.HUD) continue;
            if (category == Module.Category.FUNNY && !Swedenhack.commandManager.isFunnyVisible()) continue;
            Widget panel = new Widget(category.getName(), category, x += spacing, 6, true);
            Swedenhack.moduleManager.stream()
                    .filter(m -> m.getCategory() == category && !m.hidden)
                    .map(ModuleButton::new)
                    .forEach(panel::addButton);
            this.widgets.add(panel);
        }
        this.widgets.forEach(components -> components.getItems().sort(Comparator.comparing(Feature::getName)));

        for (Widget panel : this.widgets) {
            if (panel.getCategory() == Module.Category.CLIENT) {
                this.searchBar = new SearchBar();
                panel.getItems().add(0, this.searchBar);
                break;
            }
        }
    }

    @Override
    public void init() {
        super.init();
        openTime = System.currentTimeMillis();
        closing = false;
        alpha = 0f;
        GuiNavigator.reset();
    }

    @Override
    public void onClose() {
        if (!closing) {
            closing = true;
            closeTime = System.currentTimeMillis();
            if (searchBar != null) searchBar.clear();
            GuiNavigator.reset();
        }
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();
        if (!closing) {
            alpha = Math.min(1f, (float) (now - openTime) / FADE_DURATION);
        } else {
            alpha = Math.max(0f, 1f - (float) (now - closeTime) / FADE_DURATION);
            if (alpha <= 0f) {
                minecraft.setScreen(null);
                return;
            }
        }

        GuiFade.alpha = alpha;
        Item.context = context;
        context.fill(0, 0, context.guiWidth(), context.guiHeight(), GuiFade.apply(GuiTheme.SCREEN_DIM));
        drawMenuEffect(context, alpha);
        float s = getScale();
        int sx = (int) (mouseX / s);
        int sy = (int) (mouseY / s);
        context.pose().pushMatrix();
        context.pose().scale(s, s);
        this.widgets.forEach(components -> components.drawScreen(context, sx, sy, delta));
        context.pose().popMatrix();
        GuiFade.alpha = 1f;
    }

    // ── Menu background effects ────────────────────────────────────────────────

    private void drawMenuEffect(GuiGraphics ctx, float fadeAlpha) {
        ClickGuiModule cfg = ClickGuiModule.getInstance();
        if (cfg == null) return;
        ClickGuiModule.MenuEffect effect = cfg.getMenuEffect();
        if (effect == ClickGuiModule.MenuEffect.NONE) return;

        int w = ctx.guiWidth();
        int h = ctx.guiHeight();
        float ea = cfg.getMenuEffectAlpha() * fadeAlpha;
        float spd = cfg.getMenuEffectSpeed();
        long now = System.currentTimeMillis();
        float t = (now % 1_000_000L) / 1000.0f * spd;

        switch (effect) {
            case GRADIENT -> drawGradientEffect(ctx, w, h, t, ea);
            case RAINBOW  -> drawRainbowEffect(ctx, w, h, t, ea);
            case PLASMA   -> drawPlasmaEffect(ctx, w, h, t, ea);
            case MATRIX   -> drawMatrixEffect(ctx, w, h, t, ea);
            case VIGNETTE -> drawVignetteEffect(ctx, w, h, ea);
            case SCANLINES -> drawScanlinesEffect(ctx, w, h, t, ea);
        }
    }

    /** Smooth two-corner diagonal gradient that slowly shifts hue */
    private void drawGradientEffect(GuiGraphics ctx, int w, int h, float t, float ea) {
        float hue1 = (t * 0.04f) % 1.0f;
        float hue2 = (hue1 + 0.33f) % 1.0f;
        Color c1 = Color.getHSBColor(hue1, 0.7f, 0.5f);
        Color c2 = Color.getHSBColor(hue2, 0.7f, 0.5f);
        Color tl = withAlpha(c1, ea); Color tr = withAlpha(c2, ea);
        Color bl = withAlpha(c2, ea); Color br = withAlpha(c1, ea);
        RenderUtil.gradient(ctx, 0, 0, w, h, tl.getRGB(), bl.getRGB(), br.getRGB(), tr.getRGB());
    }

    /** Full-width horizontal rainbow bands */
    private void drawRainbowEffect(GuiGraphics ctx, int w, int h, float t, float ea) {
        int bands = 8;
        float bandH = (float) h / bands;
        for (int i = 0; i < bands; i++) {
            float hue1 = ((float) i / bands + t * 0.05f) % 1.0f;
            float hue2 = ((float) (i + 1) / bands + t * 0.05f) % 1.0f;
            Color top = withAlpha(Color.getHSBColor(hue1, 0.75f, 0.6f), ea);
            Color bot = withAlpha(Color.getHSBColor(hue2, 0.75f, 0.6f), ea);
            int y1 = Math.round(i * bandH);
            int y2 = Math.round((i + 1) * bandH);
            RenderUtil.gradient(ctx, 0, y1, w, y2, top.getRGB(), bot.getRGB(), bot.getRGB(), top.getRGB());
        }
    }

    /** Plasma: overlapping sine bands in X and Y, producing a psychedelic shimmer */
    private void drawPlasmaEffect(GuiGraphics ctx, int w, int h, float t, float ea) {
        // Coarse grid — each cell is 12px for performance
        int cell = 12;
        int cols = w / cell + 1;
        int rows = h / cell + 1;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                float fx = (float) col / cols;
                float fy = (float) row / rows;
                float v  = (float) (Math.sin(fx * 10.0 + t * 2.5)
                                  + Math.sin(fy * 10.0 + t * 2.0)
                                  + Math.sin((fx + fy) * 8.0 + t * 1.7)
                                  + Math.sin(Math.sqrt((fx - 0.5) * (fx - 0.5) + (fy - 0.5) * (fy - 0.5)) * 12.0 - t * 3.0));
                float hue = (v / 8.0f + 0.5f) % 1.0f;
                Color c = withAlpha(Color.getHSBColor(hue, 0.85f, 0.9f), ea);
                int x1 = col * cell; int y1 = row * cell;
                ctx.fill(x1, y1, x1 + cell, y1 + cell, GuiFade.apply(c.getRGB()));
            }
        }
    }

    /** Matrix: columns of falling bright pixels on a dark-green field */
    private void drawMatrixEffect(GuiGraphics ctx, int w, int h, float t, float ea) {
        int colW = 8;
        int cols = w / colW;
        java.util.Random rng = new java.util.Random(42L);
        for (int col = 0; col < cols; col++) {
            float colSeed = rng.nextFloat();
            rng.nextFloat(); // consume
            float scroll = (t * (0.6f + colSeed * 0.8f) + colSeed * h) % h;
            int trailLen = (int) (h * (0.15f + colSeed * 0.25f));
            for (int py = 0; py < trailLen; py++) {
                int screenY = ((int) scroll - py + h) % h;
                float fade = 1.0f - (float) py / trailLen;
                int green = (int) (200 * fade * ea);
                int alpha = (int) (180 * fade * ea);
                int color = (alpha << 24) | (0 << 16) | (green << 8) | 0;
                ctx.fill(col * colW, screenY, col * colW + colW - 1, screenY + 1, color);
            }
            // bright head
            int head = (int) scroll % h;
            int headAlpha = (int) (220 * ea);
            int headColor = (headAlpha << 24) | (0x50 << 16) | (0xFF << 8) | 0x50;
            ctx.fill(col * colW, head, col * colW + colW - 1, head + 2, headColor);
        }
    }

    /** Vignette: dark radial gradient from edges inward */
    private void drawVignetteEffect(GuiGraphics ctx, int w, int h, float ea) {
        int a = (int) (200 * ea);
        int dark = (a << 24);
        int clear = 0;
        int edgeW = w / 3;
        int edgeH = h / 3;
        // Left edge
        RenderUtil.gradient(ctx, 0, 0, edgeW, h, dark, dark, clear, clear);
        // Right edge
        RenderUtil.gradient(ctx, w - edgeW, 0, w, h, clear, clear, dark, dark);
        // Top edge
        RenderUtil.gradient(ctx, 0, 0, w, edgeH, dark, clear, clear, dark);
        // Bottom edge
        RenderUtil.gradient(ctx, 0, h - edgeH, w, h, clear, dark, dark, clear);
    }

    /** Scanlines: thin horizontal dark stripes + a slow bright sweep */
    private void drawScanlinesEffect(GuiGraphics ctx, int w, int h, float t, float ea) {
        // Dim every-other 2px stripe
        int lineAlpha = (int) (60 * ea);
        int lineColor = (lineAlpha << 24);
        for (int y = 0; y < h; y += 4) {
            ctx.fill(0, y, w, y + 2, lineColor);
        }
        // Sweep beam
        float beamY = (t * 0.3f % 1.0f) * h;
        int beamHeight = h / 6;
        for (int dy = 0; dy < beamHeight; dy++) {
            int screenY = ((int) beamY + dy) % h;
            float fade = 1.0f - (float) dy / beamHeight;
            int beamAlpha = (int) (45 * fade * ea);
            ctx.fill(0, screenY, w, screenY + 1, (beamAlpha << 24) | 0x00FFFFFF);
        }
    }

    private static Color withAlpha(Color c, float alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (255 * Math.min(1f, alpha)));
    }

    // ─────────────────────────────────────────────────────────────────────────

    public static float getScale() {
        return 1f;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (closing) return true;
        float s = getScale();
        int mx = (int) (click.x() / s);
        int my = (int) (click.y() / s);
        this.widgets.forEach(components -> components.mouseClicked(mx, my, click.button()));
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (closing) return true;
        float s = getScale();
        int mx = (int) (click.x() / s);
        int my = (int) (click.y() / s);
        this.widgets.forEach(components -> components.mouseReleased(mx, my, click.button()));
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (closing) return true;
        float s = getScale();
        int mx = (int) (mouseX / s);
        int my = (int) (mouseY / s);
        for (Widget widget : this.widgets) {
            if (widget.isHoveringBody(mx, my)) {
                widget.scroll(verticalAmount);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (closing) return true;

        if (TextBox.hasActiveFocus()) {
            TextBox.routeKeyPressed(input.input());
            return true;
        }

        if (GuiNavigator.handleKey(input.input())) {
            return true;
        }
        this.widgets.forEach(component -> component.onKeyPressed(input.input()));
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (closing) return true;
        if (TextBox.hasActiveFocus()) {
            TextBox.routeKeyTyped(input.codepointAsString());
            return true;
        }
        this.widgets.forEach(component -> component.onKeyTyped(input.codepointAsString(), input.modifiers()));
        return super.charTyped(input);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
    @Override
    public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
    }

    public void reload() {
        this.widgets.clear();
        load();
    }

    public final ArrayList<Widget> getComponents() {
        return this.widgets;
    }

    public int getTextOffset() {
        return -6;
    }

    public static Color getColorClipboard() {
        return colorClipboard;
    }

    public static void setColorClipboard(Color color) {
        colorClipboard = color;
    }
}
