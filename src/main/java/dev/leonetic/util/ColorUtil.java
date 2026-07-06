package dev.leonetic.util;

import dev.leonetic.features.modules.client.ClickGuiModule;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;

public class ColorUtil {
    public static int toARGB(int r, int g, int b, int a) {
        return new Color(r, g, b, a).getRGB();
    }

    public static int toRGBA(int r, int g, int b) {
        return ColorUtil.toRGBA(r, g, b, 255);
    }

    public static int toRGBA(int r, int g, int b, int a) {
        return (r << 16) + (g << 8) + b + (a << 24);
    }

    public static int toRGBA(float r, float g, float b, float a) {
        return ColorUtil.toRGBA((int) (r * 255.0f), (int) (g * 255.0f), (int) (b * 255.0f), (int) (a * 255.0f));
    }

    public static Color rainbow(int delay) {
        double rainbowState = Math.ceil((double) (System.currentTimeMillis() + (long) delay) / 20.0);
        return Color.getHSBColor((float) ((rainbowState % 360.0) / 360.0), ClickGuiModule.getInstance().rainbowSaturation.getValue() / 255.0f, ClickGuiModule.getInstance().rainbowBrightness.getValue() / 255.0f);
    }

    private static BufferedImage logoImage = null;

    static {
        try (InputStream is = ColorUtil.class.getResourceAsStream("/assets/swedenhack/textures/effect/logo.png")) {
            if (is != null) {
                logoImage = ImageIO.read(is);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Color getSwedenLogoColor(float x, float y) {
        if (logoImage == null) {
            // Fallback gradient if loading failed
            double state = Math.ceil((double) (System.currentTimeMillis() + (long) x) / 20.0);
            double radians = (state % 360.0) * Math.PI / 180.0;
            float factor = (float) (Math.sin(radians) * 0.5f + 0.5f);
            int r = (int) (0 + factor * 255);
            int g = (int) (92 + factor * 112);
            int b = (int) (166 - factor * 166);
            return new Color(r, g, b);
        }

        // Horizontal scroll animation speed
        double scrollSpeed = 0.05;
        float timeOffset = (float) (System.currentTimeMillis() * scrollSpeed);

        float flagWidth = 128f;
        float flagHeight = 80f;

        // Wave animation
        double waveFreq = 2 * Math.PI / flagWidth;
        double waveSpeed = 0.005;
        float waveAmp = 5f;
        float waveOffset = (float) (Math.sin(x * waveFreq + System.currentTimeMillis() * waveSpeed) * waveAmp);

        float tx = (x + timeOffset) % flagWidth;
        if (tx < 0) tx += flagWidth;

        float ty = (y + waveOffset) % flagHeight;
        if (ty < 0) ty += flagHeight;

        int pixelX = (int) tx;
        if (pixelX < 0) pixelX = 0;
        if (pixelX >= 128) pixelX = 127;

        float ny = ty / flagHeight;
        int pixelY = 24 + (int) (ny * 79);
        if (pixelY < 24) pixelY = 24;
        if (pixelY >= 104) pixelY = 103;

        int rgb = logoImage.getRGB(pixelX, pixelY);
        return new Color(rgb);
    }

    public static Color sweden(int delay) {
        return getSwedenLogoColor(delay, delay);
    }

    public static int toRGBA(float[] colors) {
        if (colors.length != 4) {
            throw new IllegalArgumentException("colors[] must have a length of 4!");
        }
        return ColorUtil.toRGBA(colors[0], colors[1], colors[2], colors[3]);
    }

    public static int toRGBA(double[] colors) {
        if (colors.length != 4) {
            throw new IllegalArgumentException("colors[] must have a length of 4!");
        }
        return ColorUtil.toRGBA((float) colors[0], (float) colors[1], (float) colors[2], (float) colors[3]);
    }

    public static int toRGBA(Color color) {
        return ColorUtil.toRGBA(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
    }
}
