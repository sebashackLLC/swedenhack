package dev.leonetic.features.modules.render;

import dev.leonetic.Swedenhack;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.world.SpeedMineModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BreakIndicators -- renders break progress for blocks being mined by other players.
 *
 * <p>Detection uses {@link ClientboundBlockDestructionPacket}. Progress is time-based
 * (elapsed / 2500ms). Per-entity limit of 2 concurrent entries for doublemine support.
 * Entries evict when the block becomes air or after 2500ms without a refresh packet.</p>
 */
public class BreakIndicators extends Module {

    private static final long BREAK_TIME_MS = 2500L;

    // --- Settings ---

    private final Setting<Boolean> ignoreFriends = bool("Ignore Friends", false).setPage("General");
    private final Setting<Float> range = num("Range", 20.0f, 5.0f, 50.0f).setPage("General");

    private final Setting<Boolean> render = bool("Render", true).setPage("Render");
    private final Setting<Color> renderStartColor = color("Start Color", new Color(255, 0, 0, 255)).setPage("Render");
    private final Setting<Color> renderEndColor = color("End Color", new Color(0, 255, 0, 255)).setPage("Render");
    private final Setting<Integer> renderFillAlpha = num("Fill Opacity", 40, 0, 255).setPage("Render");
    private final Setting<Integer> renderLineAlpha = num("Line Opacity", 180, 0, 255).setPage("Render");
    private final Setting<Float> renderLineWidth = num("Line Width", 1.5f, 0.5f, 5.0f).setPage("Render");

    // --- State ---

    private final Map<BreakEntry, Long> breakingProgress = new ConcurrentHashMap<>();

    // --- Singleton ---

    private static BreakIndicators INSTANCE;
    public static BreakIndicators get() { return INSTANCE; }

    public BreakIndicators() {
        super("BreakIndicators",
                "Renders the progress of a block being broken by another player.",
                Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onDisable() {
        breakingProgress.clear();
    }

    // --- Public API ---

    public boolean isBlockBeingBroken(BlockPos pos) {
        return breakingProgress.keySet().stream().anyMatch(e -> e.pos.equals(pos));
    }

    public boolean isBlockBeingBrokenByFriend(BlockPos pos) {
        return breakingProgress.keySet().stream().anyMatch(e -> e.pos.equals(pos)
                && e.entity instanceof Player p && Swedenhack.friendManager.isFriend(p));
    }

    public boolean isBlockBeingBrokenBySelf(BlockPos pos) {
        if (mc.player == null) return false;
        int selfId = mc.player.getId();
        return breakingProgress.keySet().stream().anyMatch(e -> e.pos.equals(pos) && e.entityId == selfId);
    }

    public double getBlockBreakProgress(BlockPos pos) {
        for (Map.Entry<BreakEntry, Long> entry : breakingProgress.entrySet()) {
            if (entry.getKey().pos.equals(pos)) {
                long elapsed = System.currentTimeMillis() - entry.getValue();
                return Math.min((double) elapsed / BREAK_TIME_MS, 1.0);
            }
        }
        return 0.0;
    }

    // --- Packet listener ---

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.level == null) return;

        if (event.getPacket() instanceof ClientboundBlockDestructionPacket packet) {
            BlockPos pos = packet.getPos();
            if (!canBreak(pos)) return;

            BreakEntry existing = getEntryFromPos(pos);
            if (existing != null) {
                breakingProgress.replace(existing, System.currentTimeMillis());
            } else {
                Entity entity = mc.level.getEntity(packet.getId());
                breakingProgress.put(new BreakEntry(pos, packet.getId(), entity), System.currentTimeMillis());
            }
        }
    }

    // --- Render ---

    @Override
    @Subscribe
    public void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!render.getValue()) return;

        SpeedMineModule sm = Swedenhack.moduleManager.getModuleByClass(SpeedMineModule.class);
        boolean smActive = sm != null && sm.isEnabled();
        float rangeSq = range.getValue() * range.getValue();

        for (Map.Entry<BreakEntry, Long> mine : breakingProgress.entrySet()) {
            BreakEntry entry = mine.getKey();
            BlockPos mining = entry.pos;
            long elapsedTime = System.currentTimeMillis() - mine.getValue();

            // Limit 2 per entity.
            long count = breakingProgress.keySet().stream()
                    .filter(p -> p.entityId == entry.entityId).count();
            while (count > 2) {
                breakingProgress.entrySet().stream()
                        .filter(p -> p.getKey().entityId == entry.entityId)
                        .min(Comparator.comparingLong(Map.Entry::getValue))
                        .ifPresent(min -> breakingProgress.remove(min.getKey(), min.getValue()));
                count--;
            }

            // Evict if air or expired.
            if (mc.level.getBlockState(mining).isAir() || elapsedTime > BREAK_TIME_MS) {
                breakingProgress.remove(entry, mine.getValue());
                continue;
            }

            // Range check.
            double dist = mc.player.position().distanceToSqr(Vec3.atCenterOf(mining));
            if (dist > rangeSq) continue;

            // Skip friends.
            if (ignoreFriends.getValue() && entry.entity instanceof Player p
                    && Swedenhack.friendManager.isFriend(p)) continue;

            // Skip own SpeedMine targets.
            if (smActive && sm.alreadyBreaking(mining)) continue;

            // Get block shape.
            VoxelShape outlineShape = mc.level.getBlockState(mining).getShape(mc.level, mining);
            AABB shapeBounds;
            if (outlineShape == null || outlineShape.isEmpty()) {
                shapeBounds = new AABB(0, 0, 0, 1, 1, 1);
            } else {
                shapeBounds = outlineShape.bounds();
            }

            AABB renderBox = new AABB(
                    mining.getX() + shapeBounds.minX, mining.getY() + shapeBounds.minY, mining.getZ() + shapeBounds.minZ,
                    mining.getX() + shapeBounds.maxX, mining.getY() + shapeBounds.maxY, mining.getZ() + shapeBounds.maxZ);

            Vec3 center = renderBox.getCenter();
            float scale = Math.min((float) elapsedTime / BREAK_TIME_MS, 1.0f);
            double dx = (shapeBounds.maxX - shapeBounds.minX) / 2.0;
            double dy = (shapeBounds.maxY - shapeBounds.minY) / 2.0;
            double dz = (shapeBounds.maxZ - shapeBounds.minZ) / 2.0;
            AABB scaled = new AABB(
                    center.x - dx * scale, center.y - dy * scale, center.z - dz * scale,
                    center.x + dx * scale, center.y + dy * scale, center.z + dz * scale);

            Color color = lerpColor(renderStartColor.getValue(), renderEndColor.getValue(), scale);
            int fa = renderFillAlpha.getValue();
            int la = renderLineAlpha.getValue();

            if (fa > 0)
                RenderUtil.drawBoxFilled(event.getMatrix(), scaled, withAlpha(color, fa));
            if (la > 0)
                RenderUtil.drawBox(event.getMatrix(), scaled, withAlpha(color, la), renderLineWidth.getValue());
        }
    }

    // --- Helpers ---

    private boolean canBreak(BlockPos pos) {
        if (mc.level == null) return false;
        return !mc.level.getBlockState(pos).isAir() && mc.level.getBlockState(pos).getDestroySpeed(mc.level, pos) >= 0;
    }

    private BreakEntry getEntryFromPos(BlockPos pos) {
        for (BreakEntry e : breakingProgress.keySet()) {
            if (e.pos.equals(pos)) return e;
        }
        return null;
    }

    private static Color lerpColor(Color from, Color to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (from.getRed() + (to.getRed() - from.getRed()) * t);
        int g = (int) (from.getGreen() + (to.getGreen() - from.getGreen()) * t);
        int b = (int) (from.getBlue() + (to.getBlue() - from.getBlue()) * t);
        int a = (int) (from.getAlpha() + (to.getAlpha() - from.getAlpha()) * t);
        return new Color(r, g, b, a);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    // --- Inner types ---

    private static final class BreakEntry {
        final BlockPos pos;
        final int entityId;
        final Entity entity;

        BreakEntry(BlockPos pos, int entityId, Entity entity) {
            this.pos = pos;
            this.entityId = entityId;
            this.entity = entity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BreakEntry that)) return false;
            return pos.equals(that.pos);
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }
    }
}
