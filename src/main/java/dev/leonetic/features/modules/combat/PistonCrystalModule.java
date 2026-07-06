package dev.leonetic.features.modules.combat;

import dev.leonetic.Swedenhack;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.TargetsModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.util.DamageUtil;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import dev.leonetic.mixin.client.ClientLevelAccessor;
import dev.leonetic.util.PlaceUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * PistonCrystal — places a piston + crystal + redstone source to push
 * an end crystal into an enemy player and detonates it.
 *
 * Sequence per cycle:
 * 1) Find target
 * 2) Find valid piston setup (piston pos, crystal base pos, redstone pos)
 * 3) Place piston facing the push direction
 * 4) Place crystal on obsidian/bedrock base in front of piston
 * 5) Power piston with redstone torch behind it
 * 6) Detect pushed crystal and attack it
 */
public class PistonCrystalModule extends Module {

    private static final String ROTATION_ID = "piston_crystal";

    // ─── Settings ────────────────────────────────────────────────

    private final Setting<Double>  targetRange   = num("TargetRange",   10.0, 1.0, 15.0);
    private final Setting<Double>  placeRange    = num("PlaceRange",     4.5, 1.0,  6.0);
    private final Setting<Double>  maxSelfDamage = num("MaxSelfDamage",  8.0, 0.0, 36.0);
    private final Setting<Double>  placeDelay    = num("PlaceDelay",     0.5, 0.0,  5.0);
    private final Setting<Double>  breakDelay    = num("BreakDelay",     0.1, 0.0,  2.0);
    private final Setting<Boolean> antiSuicide   = bool("AntiSuicide",  true);
    private final Setting<Boolean> pauseEat      = bool("PauseEat",     true);
    private final Setting<Boolean> swing         = bool("Swing",        true);
    private final Setting<Boolean> render        = bool("Render",       true);
    private final Setting<PistonType> pistonType = mode("PistonType",   PistonType.BOTH);

    // ─── State ───────────────────────────────────────────────────

    private Player target;
    private Setup activeSetup;
    private Phase phase = Phase.IDLE;
    private long lastPlaceMs;
    private long phaseStartMs;
    private EndCrystal trackedCrystal;

    // ─── Enums ───────────────────────────────────────────────────

    private enum PistonType { NORMAL, STICKY, BOTH }

    private enum Phase {
        IDLE,
        PLACE_PISTON,
        PLACE_CRYSTAL,
        PLACE_REDSTONE,
        WAIT_PUSH,
        BREAK_CRYSTAL
    }

    private record Setup(
            BlockPos pistonPos,
            Direction pistonFacing,
            BlockPos crystalBase,
            BlockPos crystalPos,
            BlockPos redstonePos,
            Vec3 explosionPos
    ) {}

    // ─── Constructor ─────────────────────────────────────────────

    public PistonCrystalModule() {
        super("PistonCrystal", "Pushes end crystals into players using pistons.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        resetState();
    }

    @Override
    public String getDisplayInfo() {
        return target != null ? target.getScoreboardName() : null;
    }

    private void resetState() {
        target = null;
        activeSetup = null;
        phase = Phase.IDLE;
        lastPlaceMs = 0;
        phaseStartMs = 0;
        trackedCrystal = null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tick
    // ═══════════════════════════════════════════════════════════════

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;
        if (pauseEat.getValue() && mc.player.isUsingItem()) return;

        switch (phase) {
            case IDLE          -> tickIdle();
            case PLACE_PISTON  -> tickPlacePiston();
            case PLACE_CRYSTAL -> tickPlaceCrystal();
            case PLACE_REDSTONE-> tickPlaceRedstone();
            case WAIT_PUSH     -> tickWaitPush();
            case BREAK_CRYSTAL -> tickBreakCrystal();
        }
    }

    // ─── Phase: IDLE ─────────────────────────────────────────────

    private void tickIdle() {
        if (!placeCooldownReady()) return;

        target = findTarget();
        if (target == null) return;

        Setup setup = findBestSetup(target);
        if (setup == null) return;

        activeSetup = setup;
        phase = Phase.PLACE_PISTON;
        phaseStartMs = System.currentTimeMillis();
    }

    // ─── Phase: PLACE_PISTON ─────────────────────────────────────

    private void tickPlacePiston() {
        if (target == null || activeSetup == null || isSetupInvalid()) {
            resetState();
            return;
        }

        BlockState current = mc.level.getBlockState(activeSetup.pistonPos);
        if (isPiston(current)) {
            phase = Phase.PLACE_CRYSTAL;
            phaseStartMs = System.currentTimeMillis();
            return;
        }

        if (!mc.level.getBlockState(activeSetup.pistonPos).canBeReplaced()) {
            resetState();
            return;
        }

        int normalSlot = findHotbarItem(Items.PISTON);
        int stickySlot = findHotbarItem(Items.STICKY_PISTON);

        boolean useNormal = pistonType.getValue() != PistonType.STICKY && normalSlot != -1;
        boolean useSticky = pistonType.getValue() != PistonType.NORMAL && stickySlot != -1;

        if (!useNormal && !useSticky) {
            resetState();
            return;
        }

        int slot = useNormal ? normalSlot : stickySlot;
        if (tryPlacePiston(activeSetup.pistonPos, slot, activeSetup.pistonFacing)) {
            phase = Phase.PLACE_CRYSTAL;
            phaseStartMs = System.currentTimeMillis();
        } else {
            resetState();
        }
    }

    // ─── Phase: PLACE_CRYSTAL ────────────────────────────────────

    private void tickPlaceCrystal() {
        if (target == null || activeSetup == null || isSetupInvalid()) {
            resetState();
            return;
        }

        BlockState baseState = mc.level.getBlockState(activeSetup.crystalBase);
        if (!isCrystalBaseBlock(baseState)) {
            resetState();
            return;
        }

        BlockPos crystalPos = activeSetup.crystalPos;
        if (!mc.level.getBlockState(crystalPos).isAir() || !mc.level.getBlockState(crystalPos.above()).isAir()) {
            resetState();
            return;
        }

        // Check if crystal already exists at this spot
        AABB crystalBox = new AABB(
                crystalPos.getX(), crystalPos.getY(), crystalPos.getZ(),
                crystalPos.getX() + 1, crystalPos.getY() + 2, crystalPos.getZ() + 1);
        if (!mc.level.getEntitiesOfClass(EndCrystal.class, crystalBox).isEmpty()) {
            phase = Phase.PLACE_REDSTONE;
            phaseStartMs = System.currentTimeMillis();
            return;
        }

        int crystalSlot = findHotbarItem(Items.END_CRYSTAL);
        if (crystalSlot == -1) {
            resetState();
            return;
        }

        if (placeCrystalOnBase(activeSetup.crystalBase, crystalSlot)) {
            phase = Phase.PLACE_REDSTONE;
            phaseStartMs = System.currentTimeMillis();
        } else {
            resetState();
        }
    }

    // ─── Phase: PLACE_REDSTONE ───────────────────────────────────

    private void tickPlaceRedstone() {
        if (target == null || activeSetup == null) {
            resetState();
            return;
        }

        BlockState pistonState = mc.level.getBlockState(activeSetup.pistonPos);
        if (!isPiston(pistonState)) {
            if (System.currentTimeMillis() - phaseStartMs > 1000) {
                resetState();
            }
            return;
        }

        // If piston is already extended, skip to wait
        if (pistonState.hasProperty(BlockStateProperties.EXTENDED)
                && pistonState.getValue(BlockStateProperties.EXTENDED)) {
            phase = Phase.WAIT_PUSH;
            phaseStartMs = System.currentTimeMillis();
            return;
        }

        BlockState redstoneState = mc.level.getBlockState(activeSetup.redstonePos);
        if (isRedstoneSource(redstoneState)) {
            phase = Phase.WAIT_PUSH;
            phaseStartMs = System.currentTimeMillis();
            return;
        }

        if (!mc.level.getBlockState(activeSetup.redstonePos).canBeReplaced()) {
            resetState();
            return;
        }

        int torchSlot = findHotbarItem(Items.REDSTONE_TORCH);
        int blockSlot = findHotbarItem(Items.REDSTONE_BLOCK);

        if (torchSlot == -1 && blockSlot == -1) {
            resetState();
            return;
        }

        int slot = torchSlot != -1 ? torchSlot : blockSlot;
        if (tryPlaceBlock(activeSetup.redstonePos, slot)) {
            phase = Phase.WAIT_PUSH;
            phaseStartMs = System.currentTimeMillis();
        } else {
            resetState();
        }
    }

    // ─── Phase: WAIT_PUSH ────────────────────────────────────────

    private void tickWaitPush() {
        if (activeSetup == null) {
            resetState();
            return;
        }

        // Look for crystal that has been pushed
        BlockPos pushedPos = activeSetup.crystalPos.relative(activeSetup.pistonFacing);
        AABB searchBox = new AABB(pushedPos).inflate(1.0);
        List<EndCrystal> crystals = mc.level.getEntitiesOfClass(EndCrystal.class, searchBox);

        if (!crystals.isEmpty()) {
            trackedCrystal = crystals.getFirst();
            phase = Phase.BREAK_CRYSTAL;
            phaseStartMs = System.currentTimeMillis();
            return;
        }

        // Check if crystal is still at original pos (not yet pushed)
        AABB origBox = new AABB(activeSetup.crystalPos).inflate(0.5);
        List<EndCrystal> origCrystals = mc.level.getEntitiesOfClass(EndCrystal.class, origBox);
        if (!origCrystals.isEmpty()) {
            if (System.currentTimeMillis() - phaseStartMs > 2000) {
                resetState();
            }
            return;
        }

        // No crystal found — timeout
        if (System.currentTimeMillis() - phaseStartMs > 1000) {
            resetState();
        }
    }

    // ─── Phase: BREAK_CRYSTAL ────────────────────────────────────

    private void tickBreakCrystal() {
        if (trackedCrystal == null || trackedCrystal.isRemoved()) {
            lastPlaceMs = System.currentTimeMillis();
            resetState();
            return;
        }

        long elapsed = System.currentTimeMillis() - phaseStartMs;
        if (elapsed < (long) (breakDelay.getValue() * 1000)) return;

        attackCrystal(trackedCrystal);
        lastPlaceMs = System.currentTimeMillis();
        resetState();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Setup calculation
    // ═══════════════════════════════════════════════════════════════

    private Setup findBestSetup(Player target) {
        Vec3 playerEyes = mc.player.getEyePosition();
        BlockPos targetFeet = target.blockPosition();

        Setup bestSetup = null;
        double bestSelfDmgReduction = Double.MAX_VALUE;

        // For each horizontal direction, try piston setups that push crystal toward target
        for (Direction pushDir : Direction.Plane.HORIZONTAL) {
            for (BlockPos crystalBase : getCandidateCrystalBases(targetFeet, pushDir)) {
                BlockPos crystalPos = crystalBase.above();
                BlockPos pistonPos = crystalPos.relative(pushDir.getOpposite());

                BlockPos pushedCrystalPos = crystalPos.relative(pushDir);
                Vec3 explosionPos = Vec3.atBottomCenterOf(pushedCrystalPos).add(0, 1, 0);

                if (playerEyes.distanceToSqr(Vec3.atCenterOf(pistonPos)) > placeRange.getValue() * placeRange.getValue()) continue;
                if (playerEyes.distanceToSqr(Vec3.atCenterOf(crystalBase)) > placeRange.getValue() * placeRange.getValue()) continue;

                BlockPos redstonePos = findRedstonePos(pistonPos, pushDir);
                if (redstonePos == null) continue;
                if (playerEyes.distanceToSqr(Vec3.atCenterOf(redstonePos)) > placeRange.getValue() * placeRange.getValue()) continue;

                if (!canUsePistonPos(pistonPos)) continue;
                if (!canUseCrystalBase(crystalBase, crystalPos)) continue;
                if (!canUseRedstonePos(redstonePos)) continue;

                // Pushed crystal pos must be clear or replaceable
                BlockState pushedState = mc.level.getBlockState(pushedCrystalPos);
                BlockState pushedAboveState = mc.level.getBlockState(pushedCrystalPos.above());
                if (!pushedState.isAir() && !pushedState.canBeReplaced()) continue;
                if (!pushedAboveState.isAir() && !pushedAboveState.canBeReplaced()) continue;

                // Piston pos must not be the same as crystal base
                if (pistonPos.equals(crystalBase)) continue;
                // Redstone pos must not collide with crystal or pushed crystal
                if (redstonePos.equals(crystalPos) || redstonePos.equals(pushedCrystalPos)) continue;

                float selfDmg = DamageUtil.crystalMaxSelfDamage(explosionPos);
                if (selfDmg > maxSelfDamage.getValue()) continue;
                if (antiSuicide.getValue() && selfDmg >= mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;

                // Prefer setups with lower self damage (closer to target is generally better)
                double distToTarget = explosionPos.distanceToSqr(target.position());
                if (distToTarget < bestSelfDmgReduction) {
                    bestSelfDmgReduction = distToTarget;
                    bestSetup = new Setup(pistonPos, pushDir, crystalBase, crystalPos, redstonePos, explosionPos);
                }
            }
        }

        return bestSetup;
    }

    private List<BlockPos> getCandidateCrystalBases(BlockPos targetFeet, Direction pushDir) {
        List<BlockPos> bases = new ArrayList<>();
        BlockPos col = targetFeet.relative(pushDir.getOpposite());
        for (int dy = -2; dy <= 1; dy++) {
            bases.add(col.offset(0, dy, 0));
        }
        return bases;
    }

    private BlockPos findRedstonePos(BlockPos pistonPos, Direction pistonFacing) {
        // Prefer behind the piston
        BlockPos behindPos = pistonPos.relative(pistonFacing.getOpposite());
        if (canUseRedstonePos(behindPos) && !behindPos.equals(pistonPos.relative(pistonFacing))) {
            return behindPos;
        }

        // Try other adjacent faces
        for (Direction dir : Direction.values()) {
            if (dir == pistonFacing) continue;
            BlockPos side = pistonPos.relative(dir);
            if (canUseRedstonePos(side)) return side;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Validation helpers
    // ═══════════════════════════════════════════════════════════════

    private boolean canUsePistonPos(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (isPiston(state)) return true;
        if (!state.canBeReplaced()) return false;
        if (mc.player.getBoundingBox().intersects(new AABB(pos))) return false;
        if (!PlaceUtil.noEntityCollision(pos)) return false;
        return findSolidFace(pos) != null;
    }

    private boolean canUseCrystalBase(BlockPos base, BlockPos crystalPos) {
        BlockState baseState = mc.level.getBlockState(base);
        if (!isCrystalBaseBlock(baseState)) return false;
        if (!mc.level.getBlockState(crystalPos).isAir()) return false;
        if (!mc.level.getBlockState(crystalPos.above()).isAir()) return false;

        AABB box = new AABB(crystalPos.getX(), crystalPos.getY(), crystalPos.getZ(),
                crystalPos.getX() + 1, crystalPos.getY() + 2, crystalPos.getZ() + 1);
        return mc.level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player) && e.isAlive()).isEmpty();
    }

    private boolean canUseRedstonePos(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (isRedstoneSource(state)) return true;
        if (!state.canBeReplaced()) return false;
        if (mc.player.getBoundingBox().intersects(new AABB(pos))) return false;
        if (!PlaceUtil.noEntityCollision(pos)) return false;
        return findSolidFace(pos) != null;
    }

    private boolean isPiston(BlockState state) {
        return state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON);
    }

    private boolean isCrystalBaseBlock(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private boolean isRedstoneSource(BlockState state) {
        return state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)
                || state.is(Blocks.REDSTONE_BLOCK);
    }

    private boolean isSetupInvalid() {
        if (target == null || target.isRemoved() || target.isDeadOrDying()) return true;
        if (activeSetup == null) return true;
        Vec3 eyes = mc.player.getEyePosition();
        return eyes.distanceToSqr(Vec3.atCenterOf(activeSetup.pistonPos)) > (placeRange.getValue() + 1) * (placeRange.getValue() + 1);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════════════════════════

    private boolean tryPlacePiston(BlockPos pos, int slot, Direction pushDir) {
        PlaceInfo place = findSolidFace(pos);
        if (place == null) return false;

        float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(pos));
        Direction lookDir = pushDir.getOpposite();
        float yaw = lookDir.toYRot();
        float pitch = Math.max(-40f, Math.min(40f, angles[1]));

        Swedenhack.rotationManager.submit(new RotationRequest(
                ROTATION_ID, 75, yaw, pitch, RotationRequest.Mode.SILENT));

        int prevSlot = InventoryUtil.selected();
        InventoryUtil.swap(slot);
        placeBlock(place.pos, place.face);
        InventoryUtil.swap(prevSlot);
        return true;
    }

    private boolean tryPlaceBlock(BlockPos pos, int slot) {
        PlaceInfo place = findSolidFace(pos);
        if (place == null) return false;

        float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(pos));
        Swedenhack.rotationManager.submit(new RotationRequest(
                ROTATION_ID, 70, angles[0], angles[1], RotationRequest.Mode.SILENT));

        int prevSlot = InventoryUtil.selected();
        InventoryUtil.swap(slot);
        placeBlock(place.pos, place.face);
        InventoryUtil.swap(prevSlot);
        return true;
    }

    private boolean placeCrystalOnBase(BlockPos base, int crystalSlot) {
        if (mc.player == null || mc.level == null) return false;

        float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(base).add(0, 0.5, 0));
        Swedenhack.rotationManager.submit(new RotationRequest(
                ROTATION_ID, 70, angles[0], angles[1], RotationRequest.Mode.SILENT));

        boolean success = Swedenhack.placementManager.placeCrystal(base, crystalSlot, true);
        if (success && swing.getValue()) {
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
        return success;
    }

    private void attackCrystal(EndCrystal crystal) {
        if (mc.player == null || mc.getConnection() == null) return;

        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 hit = Vec3.atCenterOf(crystal.blockPosition());
        float[] angles = MathUtil.calcAngle(eyePos, hit);
        Swedenhack.rotationManager.submit(new RotationRequest(
                ROTATION_ID, 70, angles[0], angles[1], RotationRequest.Mode.SILENT));

        mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(crystal, mc.player.isShiftKeyDown()));
        if (swing.getValue()) {
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
    }

    private void placeBlock(BlockPos neighborPos, Direction face) {
        Vec3 hitVec = Vec3.atCenterOf(neighborPos).relative(face, 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, neighborPos, false);

        var conn = mc.getConnection();
        if (conn == null) return;

        try (var handler = ((ClientLevelAccessor) mc.level).swedenhack$getBlockStatePredictionHandler().startPredicting()) {
            conn.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, handler.currentSequence()));
        }

        if (swing.getValue()) {
            conn.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Target finding
    // ═══════════════════════════════════════════════════════════════

    private Player findTarget() {
        if (mc.level == null || mc.player == null) return null;
        TargetsModule tm = Swedenhack.moduleManager.getModuleByClass(TargetsModule.class);
        double rangeSq = targetRange.getValue() * targetRange.getValue();
        Player best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(targetRange.getValue()))) {
            if (!(e instanceof Player player)) continue;
            if (player == mc.player) continue;
            if (player.isDeadOrDying() || player.isRemoved()) continue;
            if (tm != null && !tm.isValidTarget(player)) continue;

            double dist = mc.player.distanceToSqr(player);
            if (dist > rangeSq) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = player;
            }
        }
        return best;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════════

    private boolean placeCooldownReady() {
        return System.currentTimeMillis() - lastPlaceMs >= (long) (placeDelay.getValue() * 1000);
    }

    private int findHotbarItem(net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    private PlaceInfo findSolidFace(BlockPos target) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = target.relative(dir);
            BlockState state = mc.level.getBlockState(neighbor);
            if (!state.isAir() && !state.canBeReplaced()) {
                return new PlaceInfo(neighbor, dir.getOpposite());
            }
        }
        return null;
    }

    private record PlaceInfo(BlockPos pos, Direction face) {}

    // ═══════════════════════════════════════════════════════════════
    //  Render
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue() || activeSetup == null) return;

        Color ui = Swedenhack.colorManager.get("ui");
        Color green = new Color(0, 255, 0, 80);
        Color red = new Color(255, 0, 0, 80);

        // Piston pos
        RenderUtil.drawBoxFilled(event.getMatrix(), new AABB(activeSetup.pistonPos), new Color(ui.getRed(), ui.getGreen(), ui.getBlue(), 48));
        RenderUtil.drawBox(event.getMatrix(), new AABB(activeSetup.pistonPos), ui, 1.5f);

        // Crystal pos — green
        RenderUtil.drawBoxFilled(event.getMatrix(), new AABB(activeSetup.crystalPos), green);
        RenderUtil.drawBox(event.getMatrix(), new AABB(activeSetup.crystalPos), new Color(0, 255, 0, 96), 1.5f);

        // Redstone pos — red
        RenderUtil.drawBoxFilled(event.getMatrix(), new AABB(activeSetup.redstonePos), red);
        RenderUtil.drawBox(event.getMatrix(), new AABB(activeSetup.redstonePos), new Color(255, 0, 0, 96), 1.5f);
    }
}
