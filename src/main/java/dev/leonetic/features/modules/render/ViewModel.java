package dev.leonetic.features.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.leonetic.Swedenhack;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.client.Minecraft;

/**
 * ViewModel — transforms the first-person hand/item rendering.
 * Applies scale, position, and rotation to the PoseStack used by
 * {@code ItemInHandRenderer.renderHandsWithItems}.
 */
public class ViewModel extends Module {

    private static ViewModel INSTANCE;

    public static ViewModel getInstance() {
        if (Swedenhack.moduleManager == null) return null;
        return Swedenhack.moduleManager.getModuleByClass(ViewModel.class);
    }

    public static ViewModel get() {
        return INSTANCE;
    }

    // ─── Enums ───────────────────────────────────────────────────

    public enum AnimationMode {
        None, Float, Orbit, Punch, Jelly, Wave
    }

    // ─── Settings ────────────────────────────────────────────────

    public final Setting<Boolean> noSway = bool("No Sway", false).setPage("General");
    public final Setting<Boolean> oldAnimations = bool("Old Animations", false).setPage("General");
    public final Setting<Boolean> showSwapping = bool("Show Swapping", true).setPage("General");

    // ─── Swing ───

    public final Setting<Boolean> swingEnabled = bool("Swing", true).setPage("Swing");
    public final Setting<Float> swingSpeed = num("Speed", 1.0f, 0.1f, 5.0f).setPage("Swing");
    public final Setting<Float> swingRotation = num("Rotation", 1.0f, 0.0f, 3.0f).setPage("Swing");
    public final Setting<Float> swingYOffset = num("Y Offset", 0.0f, -0.5f, 0.5f).setPage("Swing");
    public final Setting<Float> swingZOffset = num("Z Offset", 0.0f, -0.5f, 0.5f).setPage("Swing");

    // ─── Transform ───

    public final Setting<Float> scaleX = num("Scale X", 1.0f, 0.1f, 3.0f).setPage("Transform");
    public final Setting<Float> scaleY = num("Scale Y", 1.0f, 0.1f, 3.0f).setPage("Transform");
    public final Setting<Float> scaleZ = num("Scale Z", 1.0f, 0.1f, 3.0f).setPage("Transform");

    public final Setting<Float> posX = num("Pos X", 0.0f, -2.0f, 2.0f).setPage("Transform");
    public final Setting<Float> posY = num("Pos Y", 0.0f, -2.0f, 2.0f).setPage("Transform");
    public final Setting<Float> posZ = num("Pos Z", 0.0f, -2.0f, 2.0f).setPage("Transform");

    public final Setting<Float> rotX = num("Rot X", 0.0f, -180.0f, 180.0f).setPage("Transform");
    public final Setting<Float> rotY = num("Rot Y", 0.0f, -180.0f, 180.0f).setPage("Transform");
    public final Setting<Float> rotZ = num("Rot Z", 0.0f, -180.0f, 180.0f).setPage("Transform");

    // ─── Animation ───

    public final Setting<Boolean> animEnabled = bool("Animation", true).setPage("Animation");
    public final Setting<AnimationMode> animMode = mode("Mode", AnimationMode.None).setPage("Animation");
    public final Setting<Float> animSpeed = num("Anim Speed", 1.0f, 0.1f, 5.0f).setPage("Animation");
    public final Setting<Float> animAmount = num("Amount", 0.25f, 0.0f, 2.0f).setPage("Animation");

    // ─── State ───────────────────────────────────────────────────

    private int ticks;

    // ─── Constructor ─────────────────────────────────────────────

    public ViewModel() {
        super("ViewModel", "Customizes first-person hand rendering.", Category.RENDER);
        INSTANCE = this;
    }

    // ─── Lifecycle ───────────────────────────────────────────────

    @Override
    public void onEnable() {
        ticks = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        ticks++;
    }

    // ─── Transform Application ───────────────────────────────────

    /**
     * Called by {@link dev.leonetic.mixin.render.MixinItemInHandRenderer} at
     * the HEAD of {@code renderHandsWithItems} to apply transforms.
     */
    public void applyTransforms(PoseStack poseStack, float partialTick) {
        // Static transforms (X is applied per-hand in the mixin)
        float animPx = 0f;
        float py = posY.getValue();
        float pz = posZ.getValue();

        float rx = rotX.getValue();
        float ry = rotY.getValue();
        float rz = rotZ.getValue();

        float sx = scaleX.getValue();
        float sy = scaleY.getValue();
        float sz = scaleZ.getValue();

        // Animation offsets
        if (animEnabled.getValue() && animMode.getValue() != AnimationMode.None) {
            float time = (ticks + partialTick) * animSpeed.getValue();
            float amt = animAmount.getValue();

            switch (animMode.getValue()) {
                case Float -> {
                    py += (float) Math.sin(time * 0.1) * amt * 0.1f;
                }
                case Orbit -> {
                    animPx += (float) Math.sin(time * 0.08) * amt * 0.15f;
                    py += (float) Math.cos(time * 0.08) * amt * 0.1f;
                }
                case Punch -> {
                    float punch = (float) Math.max(0, Math.sin(time * 0.15)) * amt;
                    pz -= punch * 0.2f;
                    rx += punch * 10f;
                }
                case Jelly -> {
                    float jx = (float) Math.sin(time * 0.12) * amt * 0.1f;
                    float jy = (float) Math.cos(time * 0.1) * amt * 0.1f;
                    sx += jx;
                    sy += jy;
                }
                case Wave -> {
                    rz += (float) Math.sin(time * 0.1) * amt * 15f;
                }
                default -> {}
            }
        }

        // Apply transforms: translate → rotate → scale
        // Note: static posX is applied per-hand in the mixin (negated for offhand)
        if (animPx != 0 || py != 0 || pz != 0) {
            poseStack.translate(animPx, py, pz);
        }
        if (rx != 0) poseStack.mulPose(Axis.XP.rotationDegrees(rx));
        if (ry != 0) poseStack.mulPose(Axis.YP.rotationDegrees(ry));
        if (rz != 0) poseStack.mulPose(Axis.ZP.rotationDegrees(rz));
        if (sx != 1 || sy != 1 || sz != 1) {
            poseStack.scale(sx, sy, sz);
        }
    }

    // ─── Public API for Mixin ────────────────────────────────────

    /** X translation value (applied per-hand with sign flip for offhand). */
    public float getPosX() { return posX.getValue(); }

    /** Whether camera-turn hand sway should be suppressed. */
    public boolean noSway() { return isEnabled() && noSway.getValue(); }

    /** Whether to use old-style swings without the newer hand bob translation. */
    public boolean oldAnimations() { return isEnabled() && oldAnimations.getValue(); }

    /** Whether to show equip animation when item stacks change. */
    public boolean showSwapping() { return isEnabled() && showSwapping.getValue(); }

    /** Whether the held-item swap animation should be bypassed. */
    public boolean hideSwapping() { return isEnabled() && !showSwapping.getValue(); }

    /** Whether the Swing subcategory is active. */
    public boolean swingEnabled() { return isEnabled() && swingEnabled.getValue(); }

    /** Swing speed multiplier (1.0 = normal, >1 = faster). */
    public float swingSpeed() { return swingEnabled() ? swingSpeed.getValue() : 1.0f; }

    /** Multiplier applied to swing rotation angles. */
    public float swingRotation() { return swingEnabled() ? swingRotation.getValue() : 1.0f; }

    /** Extra Y translation applied during the swing transform. */
    public float swingYOffset() { return swingEnabled() ? swingYOffset.getValue() : 0.0f; }

    /** Extra Z translation applied during the swing transform. */
    public float swingZOffset() { return swingEnabled() ? swingZOffset.getValue() : 0.0f; }
}
