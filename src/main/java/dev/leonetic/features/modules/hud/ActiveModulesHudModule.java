package dev.leonetic.features.modules.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.leonetic.Swedenhack;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.ClickGuiModule;
import dev.leonetic.features.modules.client.HudClientModule;
import dev.leonetic.features.modules.client.HudModule;
import dev.leonetic.features.settings.Bind;
import dev.leonetic.util.traits.Jsonable;
import net.minecraft.client.gui.GuiGraphics;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class ActiveModulesHudModule extends HudModule implements Jsonable {
    private static final int RIGHT_MARGIN = 2;
    private static final int BOTTOM_MARGIN = 2;

    private static final int BOTTOM_RIGHT_GAP = 2;
    private static final int GRAY = 0xFFAAAAAA;

    public enum SnapTo {
        TOP_LEFT, TOP_RIGHT, CENTER_LEFT, CENTER_RIGHT, BOTTOM_LEFT, DEFAULT, BOTTOMRIGHT
    }

    private static ActiveModulesHudModule INSTANCE;

    private final ArrayList<String> entries = new ArrayList<>();

    public ActiveModulesHudModule() {
        super("ActiveModules");
        INSTANCE = this;
        Swedenhack.configManager.addConfig(this);
    }

    public static ActiveModulesHudModule getInstance() {
        return INSTANCE;
    }

    public boolean add(String name) {
        Module module = Swedenhack.moduleManager.getModuleByName(name);
        if (module == null) return false;
        String key = module.getName();
        for (String e : entries) {
            if (e.equalsIgnoreCase(key)) return false;
        }
        entries.add(key);
        return true;
    }

    public boolean remove(String name) {
        Module module = Swedenhack.moduleManager.getModuleByName(name);
        String key = module != null ? module.getName() : name;
        return entries.removeIf(e -> e.equalsIgnoreCase(key));
    }

    public void clear() {
        entries.clear();
    }

    public List<String> getEntries() {
        return new ArrayList<>(entries);
    }

    @Override
    public void render(Render2DEvent event) {
        if (entries.isEmpty()) return;

        GuiGraphics ctx = event.getContext();
        HudClientModule hudClient = Swedenhack.moduleManager.getModuleByClass(HudClientModule.class);
        ClickGuiModule clickGui = ClickGuiModule.getInstance();
        
        int activeColor = hudClient != null
                ? hudClient.activeModuleColor.getValue().getRGB()
                : Swedenhack.colorManager.getAsIntFullAlpha("chat");
        boolean useGradient = false;
        
        if (hudClient != null && hudClient.syncColor.getValue() && clickGui != null) {
            if (clickGui.theme.getValue() == ClickGuiModule.Theme.GRADIENT) {
                useGradient = true;
            } else {
                activeColor = Swedenhack.colorManager.getAsIntFullAlpha("ui");
            }
        }

        SnapTo snap = hudClient != null
                ? SnapTo.valueOf(hudClient.activeModulesSnap.getValue().name())
                : SnapTo.DEFAULT;
        int maxWidth = 0;
        for (String name : entries) {
            Module module = Swedenhack.moduleManager.getModuleByName(name);
            if (module == null) continue;
            String display = module.getDisplayName();
            Bind bind = module.getBind().getKey() > 0 ? module.getBind() : null;
            String suffix = bind != null ? " [" + bind + "]" : "";
            maxWidth = Math.max(maxWidth, mc.font.width(display) + mc.font.width(suffix));
        }

        int totalHeight = entries.size() * mc.font.lineHeight;
        int startY = startY(snap, hudClient, totalHeight);

        for (int i = 0; i < entries.size(); i++) {
            String name = entries.get(i);
            Module module = Swedenhack.moduleManager.getModuleByName(name);
            if (module == null) continue;

            String display = module.getDisplayName();
            Bind bind = module.getBind().getKey() > 0 ? module.getBind() : null;
            String suffix = bind != null ? " [" + bind + "]" : "";
            int lineWidth = mc.font.width(display) + mc.font.width(suffix);

            int x = xForSnap(snap, lineWidth, maxWidth);

            int nameColor;
            if (useGradient && module.isEnabled()) {
                nameColor = clickGui.gradientAt(startY).getRGB();
            } else {
                nameColor = module.isEnabled() ? activeColor : GRAY;
            }
            
            ctx.drawString(mc.font, display, x, startY, nameColor);
            if (!suffix.isEmpty()) {
                int bindColorVal = module.isEnabled() ? bindColor(module, startY) : GRAY;
                ctx.drawString(mc.font, suffix, x + mc.font.width(display), startY, bindColorVal);
            }

            startY += mc.font.lineHeight;
        }
    }

    /** Compute the starting Y for the whole list */
    private int startY(SnapTo snap, HudClientModule hudClient, int totalHeight) {
        switch (snap) {
            case TOP_LEFT:
            case TOP_RIGHT:
                return 2;
            case CENTER_LEFT:
            case CENTER_RIGHT:
            case DEFAULT:
                return screenHeight() / 2;
            case BOTTOM_LEFT:
            case BOTTOMRIGHT:
                return bottomRightTop(hudClient);
            default:
                return screenHeight() / 2;
        }
    }

    /** Compute the X position for a line given the snap point */
    private int xForSnap(SnapTo snap, int lineWidth, int maxWidth) {
        switch (snap) {
            case TOP_LEFT:
            case CENTER_LEFT:
            case BOTTOM_LEFT:
                return 2;
            case TOP_RIGHT:
            case CENTER_RIGHT:
            case DEFAULT:
            case BOTTOMRIGHT:
            default:
                return screenWidth() - RIGHT_MARGIN - lineWidth;
        }
    }

    private int bindColor(Module module, float startY) {
        ClickGuiModule gui = ClickGuiModule.getInstance();
        if (gui == null) return GRAY;
        
        if (gui.theme.getValue() == ClickGuiModule.Theme.GRADIENT) {
            Color gradientColor = gui.gradientAt(startY);
            return new Color(gradientColor.getRed(), gradientColor.getGreen(), gradientColor.getBlue(), 255).getRGB();
        }
        
        Color accent = gui.categoryAccent(module.getCategory());
        return new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 255).getRGB();
    }

    private int bottomRightTop(HudClientModule hudClient) {
        int linesBelow = 0;
        if (hudClient != null) {
            if (hudClient.isElementEnabled(CoordinatesHudModule.class)) linesBelow++;
            if (hudClient.isElementEnabled(PingHudModule.class)) linesBelow++;

            if (hudClient.isElementEnabled(RadarHudModule.class)) {
                RadarHudModule radar = hudClient.getElement(RadarHudModule.class);
                if (radar != null) linesBelow += radar.renderedLineCount();
            }
        }
        int blockBottom = bottomAnchor() - BOTTOM_MARGIN - linesBelow * mc.font.lineHeight;
        if (linesBelow > 0) blockBottom -= BOTTOM_RIGHT_GAP;
        return blockBottom - entries.size() * mc.font.lineHeight;
    }

    @Override
    public JsonElement toJson() {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        for (String entry : entries) array.add(entry);
        object.add("entries", array);
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        entries.clear();
        JsonElement arr = element.getAsJsonObject().get("entries");
        if (arr == null || !arr.isJsonArray()) return;
        for (JsonElement e : arr.getAsJsonArray()) entries.add(e.getAsString());
    }

    @Override
    public String getFileName() {
        return "active_modules_hud.json";
    }
}
