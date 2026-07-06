package dev.leonetic.features.modules.render;

import dev.leonetic.Swedenhack;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.render.MatrixCapture;
import org.joml.Matrix3x2fStack;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders item-icon markers in world for notable events: ender-pearl throws,
 * chorus-fruit teleports, and firework rocket launches. Each icon shows the
 * pack-styled item icon over a colored circular background, fades out over
 * its configured duration, and stays anchored at the event position.
 */
public class IconsModule extends Module {

    /* ─── settings ─── */

    public final Setting<Float> scale = num("Scale", 1.0f, 0.25f, 4.0f).setPage("General");
    public final Setting<Boolean> dynamicSize = bool("Dynamic Size", true).setPage("General");
    public final Setting<Float> dynamicReference = num("Reference Distance", 16.0f, 2.0f, 64.0f).setPage("General")
            .setVisibility(v -> dynamicSize.getValue());
    public final Setting<Float> dynamicMin = num("Min Scale", 0.25f, 0.05f, 1.0f).setPage("General")
            .setVisibility(v -> dynamicSize.getValue());
    public final Setting<Float> dynamicMax = num("Max Scale", 2.5f, 1.0f, 5.0f).setPage("General")
            .setVisibility(v -> dynamicSize.getValue());

    public final Setting<Boolean> pearls = bool("Pearls", true).setPage("Pearls");
    public final Setting<Color> pearlColor = color("Pearl Color", new Color(0x1E, 0x83, 0x59, 255)).setPage("Pearls")
            .setVisibility(v -> pearls.getValue());
    public final Setting<Integer> pearlDuration = num("Pearl Duration", 1500, 250, 10000).setPage("Pearls")
            .setVisibility(v -> pearls.getValue());

    public final Setting<Boolean> chorus = bool("Chorus", true).setPage("Chorus");
    public final Setting<Color> chorusColor = color("Chorus Color", new Color(0xC0, 0x93, 0xD4, 255)).setPage("Chorus")
            .setVisibility(v -> chorus.getValue());
    public final Setting<Integer> chorusDuration = num("Chorus Duration", 1500, 250, 10000).setPage("Chorus")
            .setVisibility(v -> chorus.getValue());

    public final Setting<Boolean> fireworks = bool("Fireworks", true).setPage("Fireworks");
    public final Setting<Color> fireworkColor = color("Firework Color", new Color(0xE0, 0x5A, 0x3A, 255)).setPage("Fireworks")
            .setVisibility(v -> fireworks.getValue());
    public final Setting<Integer> fireworkDuration = num("Firework Duration", 2000, 250, 10000).setPage("Fireworks")
            .setVisibility(v -> fireworks.getValue());

    public final Setting<Boolean> splash = bool("Splash", true).setPage("Splash");
    public final Setting<Boolean> splashIncludeLingering = bool("Include Lingering", true).setPage("Splash")
            .setVisibility(v -> splash.getValue());
    public final Setting<Color> splashFallbackColor = color("Splash Fallback Color", new Color(0x37, 0x5D, 0xC6, 255)).setPage("Splash")
            .setVisibility(v -> splash.getValue());
    public final Setting<Integer> splashDuration = num("Splash Duration", 1500, 250, 10000).setPage("Splash")
            .setVisibility(v -> splash.getValue());

    public final Setting<Boolean> regear = bool("Regear", true).setPage("Regear");
    public final Setting<Color> enderChestColor = color("Ender Chest Color", new Color(0x2C, 0x25, 0x40, 255)).setPage("Regear")
            .setVisibility(v -> regear.getValue());
    public final Setting<Color> shulkerColor = color("Shulker Color", new Color(0x97, 0x6D, 0xAB, 255)).setPage("Regear")
            .setVisibility(v -> regear.getValue());
    public final Setting<Integer> regearDuration = num("Regear Duration", 2500, 250, 10000).setPage("Regear")
            .setVisibility(v -> regear.getValue());
    public final Setting<Float> regearRadius = num("Attribution Radius", 5.0f, 1.0f, 16.0f).setPage("Regear")
            .setVisibility(v -> regear.getValue());

    public final Setting<Boolean> debug = bool("Debug Logs", true).setPage("General");

    /* ─── state ─── */

    private final List<Icon> icons = new CopyOnWriteArrayList<>();
    // Tracks the last time we emitted an ender-chest icon at a given block pos
    // to suppress duplicate viewer-count packets (open+immediate-close, etc).
    private final Map<Long, Long> recentEnderChestOpens = new ConcurrentHashMap<>();

    public IconsModule() {
        super("Icons", "Renders texture-pack item icons in world for pearls, chorus teleports and firework launches.", Category.RENDER);
    }

    @Override
    public void onEnable() {
        icons.clear();
        recentEnderChestOpens.clear();
        logDebug("IconsModule enabled.");
    }

    @Override
    public void onDisable() {
        icons.clear();
        recentEnderChestOpens.clear();
        logDebug("IconsModule disabled.");
    }

    private void logDebug(String msg) {
        if (debug.getValue()) {
            Swedenhack.LOGGER.info("[IconsDebug] " + msg);
        }
    }

    /* ─── detection ─── */

    @Override
    public void onTick() {
        if (nullCheck()) return;
        long now = System.currentTimeMillis();
        // Since we are using CopyOnWriteArrayList, it is safe to iterate and remove
        for (Icon icon : icons) {
            if (icon.isExpired(now)) {
                icons.remove(icon);
                logDebug("Icon expired: " + icon.kind + " at " + icon.x + ", " + icon.y + ", " + icon.z);
                continue;
            }
            if (icon.needsResolve() && mc.level != null) {
                Entity entity = mc.level.getEntity(icon.entityId);
                if (entity instanceof ThrowableItemProjectile proj) {
                    ItemStack actual = proj.getItem();
                    if (!actual.isEmpty()) {
                        icon.customStack = actual;
                        if (actual.is(Items.LINGERING_POTION)) {
                            icon.kind = IconKind.LINGERING_POTION;
                        } else {
                            icon.kind = IconKind.SPLASH_POTION;
                        }
                        PotionContents pc = actual.get(DataComponents.POTION_CONTENTS);
                        if (pc != null) {
                            icon.colorOverride = pc.getColor() | 0xFF000000;
                        }
                        logDebug("Resolved potion entity to: " + actual.getItem() + " with color " + Integer.toHexString(icon.colorOverride));
                    }
                    icon.entityId = -1;
                }
            }
        }
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();
        mc.execute(() -> {
            if (nullCheck()) return;
            long now = System.currentTimeMillis();

            if (packet instanceof ClientboundAddEntityPacket addPacket) {
                EntityType<?> type = addPacket.getType();
                logDebug("Received ClientboundAddEntityPacket: type=" + type);
                if (type == EntityType.ENDER_PEARL && pearls.getValue()) {
                    icons.add(new Icon(IconKind.PEARL, addPacket.getX(), addPacket.getY(), addPacket.getZ(), now, pearlDuration.getValue()));
                    logDebug("Added pearl icon at: " + addPacket.getX() + ", " + addPacket.getY() + ", " + addPacket.getZ());
                } else if (type == EntityType.FIREWORK_ROCKET && fireworks.getValue()) {
                    icons.add(new Icon(IconKind.FIREWORK, addPacket.getX(), addPacket.getY(), addPacket.getZ(), now, fireworkDuration.getValue()));
                    logDebug("Added firework icon at: " + addPacket.getX() + ", " + addPacket.getY() + ", " + addPacket.getZ());
                } else if (type == EntityType.SPLASH_POTION && splash.getValue()) {
                    ItemStack placeholder = new ItemStack(Items.SPLASH_POTION);
                    icons.add(new Icon(IconKind.SPLASH_POTION, addPacket.getX(), addPacket.getY(), addPacket.getZ(),
                            now, splashDuration.getValue(), placeholder, 0, addPacket.getId()));
                    logDebug("Added splash potion placeholder icon at: " + addPacket.getX() + ", " + addPacket.getY() + ", " + addPacket.getZ());
                } else if (type == EntityType.LINGERING_POTION && splash.getValue() && splashIncludeLingering.getValue()) {
                    ItemStack placeholder = new ItemStack(Items.LINGERING_POTION);
                    icons.add(new Icon(IconKind.LINGERING_POTION, addPacket.getX(), addPacket.getY(), addPacket.getZ(),
                            now, splashDuration.getValue(), placeholder, 0, addPacket.getId()));
                    logDebug("Added lingering potion placeholder icon at: " + addPacket.getX() + ", " + addPacket.getY() + ", " + addPacket.getZ());
                }
                return;
            }

            // Chorus / Enderman teleport sound detection
            if (packet instanceof ClientboundSoundPacket soundPacket && chorus.getValue()) {
                SoundEvent sound = soundPacket.getSound().value();
                if (sound == SoundEvents.CHORUS_FRUIT_TELEPORT || sound == SoundEvents.ENDERMAN_TELEPORT) {
                    icons.add(new Icon(IconKind.CHORUS, soundPacket.getX(), soundPacket.getY(), soundPacket.getZ(), now, chorusDuration.getValue()));
                    logDebug("Added chorus/teleport icon at: " + soundPacket.getX() + ", " + soundPacket.getY() + ", " + soundPacket.getZ());
                }
                return;
            }

            if (!regear.getValue()) return;

            // Shulker appearing — placement signal.
            if (packet instanceof ClientboundBlockUpdatePacket bup) {
                BlockState state = bup.getBlockState();
                if (state.getBlock() instanceof ShulkerBoxBlock shulker) {
                    BlockPos pos = bup.getPos();
                    Player attributed = nearestEligiblePlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, regearRadius.getValue());
                    if (attributed == null) return;
                    ItemStack stack = new ItemStack(shulker.asItem());
                    DyeColor dye = shulker.getColor();
                    int color = (dye != null)
                        ? (dye.getTextureDiffuseColor() | 0xFF000000)
                        : shulkerColor.getValue().getRGB();
                    icons.add(new Icon(IconKind.SHULKER, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                            now, regearDuration.getValue(), stack, color));
                    logDebug("Added shulker icon at: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                }
                return;
            }

            // Ender chest opening — viewer-count block event.
            if (packet instanceof ClientboundBlockEventPacket bep) {
                Block block = bep.getBlock();
                if (block != Blocks.ENDER_CHEST) return;
                // Type 1 = viewer count, data 0 = closing; ignore closes.
                if (bep.getB0() != 1 || bep.getB1() <= 0) return;
                // Suppress duplicate events for the same open.
                BlockPos pos = bep.getPos();
                long key = pos.asLong();
                Long last = recentEnderChestOpens.get(key);
                if (last != null && now - last < 750) return;
                recentEnderChestOpens.put(key, now);
                if (recentEnderChestOpens.size() > 64) {
                    recentEnderChestOpens.entrySet().removeIf(e -> now - e.getValue() > 5_000);
                }

                Player attributed = nearestEligiblePlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, regearRadius.getValue());
                if (attributed == null) return;
                icons.add(new Icon(IconKind.ENDER_CHEST, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        now, regearDuration.getValue()));
                logDebug("Added ender chest icon at: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
            }
        });
    }

    /**
     * Find the closest non-self, non-friend player within {@code maxDist} blocks
     * of the given world position. Returns {@code null} if no such player exists.
     */
    private static Player nearestEligiblePlayer(double x, double y, double z, double maxDist) {
        if (mc.level == null || mc.player == null) return null;
        double bestSq = maxDist * maxDist;
        Player best = null;
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            if (Swedenhack.friendManager.isFriend(p)) continue;
            double dx = p.getX() - x;
            double dy = p.getY() - y;
            double dz = p.getZ() - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    /* ─── render ─── */

    @Override
    public void onRender2D(Render2DEvent event) {
        if (mc.level == null || icons.isEmpty()) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;
        GuiGraphics graphics = event.getContext();
        Matrix3x2fStack pose = graphics.pose();
        long now = System.currentTimeMillis();
        float base = scale.getValue();

        for (Icon icon : icons) {
            float[] screen = MatrixCapture.worldToScreen(icon.x, icon.y, icon.z);
            if (screen == null) continue;
            float screenX = screen[0];
            float screenY = screen[1];

            float life = icon.life(now);
            if (life <= 0f) continue;

            // Pop-in over first 10% of life, ease-out over last 30%.
            float age = (now - icon.spawnedAt) / (float) icon.durationMs;
            float popIn = Math.min(1f, age * 10f);
            float holdLerp = Math.min(1f, life / 0.30f);
            float anim = popIn * holdLerp;
            if (anim <= 0.001f) continue;

            // Optional distance-based sizing — full size at `dynamicReference` blocks,
            // shrinks beyond that, grows closer, clamped to [min, max].
            float distMul = 1f;
            if (dynamicSize.getValue()) {
                double dx = icon.x - camera.position().x;
                double dy = icon.y - camera.position().y;
                double dz = icon.z - camera.position().z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < 0.01) dist = 0.01;
                distMul = (float) (dynamicReference.getValue() / dist);
                distMul = Math.max(dynamicMin.getValue(), Math.min(dynamicMax.getValue(), distMul));
            }

            // Fixed-size icons in screen space; `base` setting acts as a direct multiplier.
            // 1.0 base ≈ 12-pixel inner / 14-pixel outer radius.
            float s = base * distMul * anim;
            if (s <= 0.001f) continue;

            int bgARGB = 0x64000000;
            int colorARGB = icon.colorOverride != 0 ? icon.colorOverride : colorFor(icon.kind);

            float bgR = 12f * s;
            float fgR = 10f * s;

            pose.pushMatrix();
            pose.translate(screenX, screenY);
            drawSmoothCircle(graphics, bgR, bgARGB);
            drawSmoothCircle(graphics, fgR, colorARGB);

            // Item icon sized to fit inside the colored circle.
            float iconPx = fgR * 1.6f;
            float iconScale = iconPx / 16f;
            pose.pushMatrix();
            pose.scale(iconScale, iconScale);
            graphics.renderItem(icon.stack(), -8, -8);
            pose.popMatrix();

            pose.popMatrix();
        }
    }

    private int colorFor(IconKind kind) {
        return switch (kind) {
            case PEARL       -> pearlColor.getValue().getRGB();
            case CHORUS      -> chorusColor.getValue().getRGB();
            case FIREWORK    -> fireworkColor.getValue().getRGB();
            case ENDER_CHEST -> enderChestColor.getValue().getRGB();
            case SHULKER     -> shulkerColor.getValue().getRGB();
            case SPLASH_POTION,
                 LINGERING_POTION -> splashFallbackColor.getValue().getRGB();
        };
    }

    /**
     * Smooth filled circle centered at (0,0) in the current pose. Oversamples at
     * physical-pixel resolution so the edge stays clean at any GUI scale —
     * equivalent in spirit to a triangle-fan circle but works through the
     * existing {@code GuiGraphics.fill} pipeline (which auto-batches via the
     * GUI pipeline & blend state, no manual RenderSystem juggling required).
     */
    private static void drawSmoothCircle(GuiGraphics graphics, float radius, int color) {
        if (radius < 0.25f) return;
        float guiScale = (float) mc.getWindow().getGuiScale();
        if (guiScale < 1f) guiScale = 1f;
        Matrix3x2fStack pose = graphics.pose();
        float inv = 1f / guiScale;
        pose.pushMatrix();
        pose.scale(inv, inv);

        float pr = radius * guiScale;
        int ipr = (int) Math.ceil(pr);
        float r2 = pr * pr;
        for (int dy = -ipr; dy <= ipr; dy++) {
            float d2 = r2 - (float) dy * dy;
            if (d2 <= 0) continue;
            int dx = (int) Math.round(Math.sqrt(d2));
            if (dx > 0) graphics.fill(-dx, dy, dx, dy + 1, color);
        }
        pose.popMatrix();
    }

    /* ─── inner types ─── */

    private enum IconKind {
        PEARL, CHORUS, FIREWORK, ENDER_CHEST, SHULKER, SPLASH_POTION, LINGERING_POTION;
        ItemStack stack() {
            return switch (this) {
                case PEARL            -> new ItemStack(Items.ENDER_PEARL);
                case CHORUS           -> new ItemStack(Items.CHORUS_FRUIT);
                case FIREWORK         -> new ItemStack(Items.FIREWORK_ROCKET);
                case ENDER_CHEST      -> new ItemStack(Items.ENDER_CHEST);
                case SHULKER          -> new ItemStack(Items.SHULKER_BOX);
                case SPLASH_POTION    -> new ItemStack(Items.SPLASH_POTION);
                case LINGERING_POTION -> new ItemStack(Items.LINGERING_POTION);
            };
        }
    }

    private static final class Icon {
        IconKind kind;
        final double x, y, z;
        final long spawnedAt;
        final int durationMs;
        /** Optional explicit ItemStack (used for dyed shulker variants); null = use kind default. */
        ItemStack customStack;
        /** Optional explicit ARGB color; 0 = use kind default. */
        int colorOverride;
        /** Entity id used to resolve {@code customStack}/{@code colorOverride} on the next tick (-1 = nothing to resolve). */
        int entityId = -1;

        Icon(IconKind kind, double x, double y, double z, long spawnedAt, int durationMs) {
            this(kind, x, y, z, spawnedAt, durationMs, null, 0, -1);
        }

        Icon(IconKind kind, double x, double y, double z, long spawnedAt, int durationMs,
             ItemStack customStack, int colorOverride) {
            this(kind, x, y, z, spawnedAt, durationMs, customStack, colorOverride, -1);
        }

        Icon(IconKind kind, double x, double y, double z, long spawnedAt, int durationMs,
             ItemStack customStack, int colorOverride, int entityId) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.spawnedAt = spawnedAt;
            this.durationMs = Math.max(1, durationMs);
            this.customStack = customStack;
            this.colorOverride = colorOverride;
            this.entityId = entityId;
        }

        boolean isExpired(long now) {
            return now - spawnedAt >= durationMs;
        }

        boolean needsResolve() {
            return entityId >= 0;
        }

        /** Remaining life in [0..1]. 1 = just spawned, 0 = expired. */
        float life(long now) {
            float t = (now - spawnedAt) / (float) durationMs;
            return 1f - Math.max(0f, Math.min(1f, t));
        }

        ItemStack stack() {
            return customStack != null ? customStack : kind.stack();
        }
    }
}
