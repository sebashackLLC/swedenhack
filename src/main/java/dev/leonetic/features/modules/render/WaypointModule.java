package dev.leonetic.features.modules.render;

import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.render.MatrixCapture;
import dev.leonetic.util.render.RenderUtil;
import dev.leonetic.util.render.font.Fonts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.*;

public class WaypointModule extends Module {

    public final Setting<String>  nameSetting    = str("Name", "Nether 0 0");
    public final Setting<Double>  x              = num("X", 0.0, -30000000.0, 30000000.0);
    public final Setting<Double>  y              = num("Y", 120.0, -64.0, 320.0);
    public final Setting<Double>  z              = num("Z", 0.0, -30000000.0, 30000000.0);
    public final Setting<Boolean> onlyNether     = bool("OnlyNether", true);
    public final Setting<Color>   color          = color("Color", 255, 0, 128, 255);
    public final Setting<Color>   sideColor      = color("SideColor", 255, 0, 128, 55);
    public final Setting<Float>   lineWidth      = num("LineWidth", 1.5f, 0.5f, 5.0f);
    public final Setting<Boolean> drawBox        = bool("DrawBox", true);
    public final Setting<Boolean> drawBeaconLine = bool("DrawBeaconLine", true);
    public final Setting<Boolean> drawTracer     = bool("DrawTracer", false);
    public final Setting<Boolean> drawNametag    = bool("DrawNametag", true);
    public final Setting<Float>   scale          = num("Scale", 1.0f, 0.1f, 3.0f);
    public final Setting<Float>   minScale       = num("MinScale", 0.3f, 0.1f, 1.0f);
    public final Setting<Color>   bgColor        = color("BgColor", 0, 0, 0, 128);

    public WaypointModule() {
        super("Waypoint", "Renders a custom waypoint (default at Nether 0 0)", Category.RENDER);
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;
        if (onlyNether.getValue() && !mc.level.dimension().equals(Level.NETHER)) return;

        double wpX = x.getValue();
        double wpY = y.getValue();
        double wpZ = z.getValue();

        Color c = color.getValue();
        Color sC = sideColor.getValue();
        float lw = lineWidth.getValue();

        if (drawBox.getValue()) {
            AABB box = new AABB(wpX, wpY, wpZ, wpX + 1.0, wpY + 1.0, wpZ + 1.0);
            RenderUtil.drawBoxFilled(event.getMatrix(), box, sC);
            RenderUtil.drawBox(event.getMatrix(), box, c, lw);
        }

        if (drawBeaconLine.getValue()) {
            Vec3 from = new Vec3(wpX + 0.5, -64.0, wpZ + 0.5);
            Vec3 to = new Vec3(wpX + 0.5, 320.0, wpZ + 0.5);
            RenderUtil.drawLine(from, to, c, lw);
        }

        if (drawTracer.getValue()) {
            Vec3 from = mc.player.position();
            Vec3 to = new Vec3(wpX + 0.5, wpY + 0.5, wpZ + 0.5);
            RenderUtil.drawLine(from, to, c, lw);
        }
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (nullCheck()) return;
        if (onlyNether.getValue() && !mc.level.dimension().equals(Level.NETHER)) return;
        if (MatrixCapture.projection == null) return;
        if (!drawNametag.getValue()) return;

        double wpX = x.getValue() + 0.5;
        double wpY = y.getValue() + 1.2;
        double wpZ = z.getValue() + 0.5;

        double dist = mc.player.position().distanceTo(new Vec3(wpX, wpY, wpZ));

        float[] screen = MatrixCapture.worldToScreen(wpX, wpY, wpZ);
        if (screen == null) return;

        GuiGraphics graphics = event.getContext();
        float anchorX = screen[0];
        float anchorY = screen[1];

        float s = (float) Math.max(minScale.getValue(), scale.getValue() * 8.0 / (dist + 8.0));

        String name = nameSetting.getValue();
        String distStr = " [" + (int) dist + "m]";

        int nameW = Fonts.width(name);
        int distW = Fonts.width(distStr);
        int totalW = nameW + distW;
        int halfW = totalW / 2;
        int textH = Fonts.lineHeight();
        int textTopY = -textH;

        graphics.pose().pushMatrix();
        graphics.pose().translate(anchorX, anchorY);
        graphics.pose().scale(s, s);

        graphics.fill(-halfW - 2, textTopY - 1, halfW + 2, 1, bgColor.getValue().getRGB());

        Fonts.drawString(graphics, name, -halfW, textTopY, color.getValue().getRGB());
        Fonts.drawString(graphics, distStr, -halfW + nameW, textTopY, 0xFFAAAAAA);

        graphics.pose().popMatrix();
    }
}
