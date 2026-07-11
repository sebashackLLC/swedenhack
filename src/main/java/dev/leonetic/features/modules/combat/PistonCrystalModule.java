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
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
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
 */
public class PistonCrystalModule extends Module {

    private static final String ROTATION_ID = "piston_crystal";

    // ─── Settings ────────────────────────────────────────────────

    private final Setting<Double>  targetRange   = num("TargetRange",   10.0, 1.0, 15.0);
    private final Setting<Double>  placeRange    = num("PlaceRange",     4.5, 1.0,  6.0);
    private final Setting<Double>  maxSelfDamage = num("MaxSelfDamage",  8.0, 0.0, 36.0);
    private final Setting<Double>  minDamage     = num("MinDamage",      4.0, 0.0, 36.0);
    private final Setting<Double>  placeDelay    = num("PlaceDelay",     0.5, 0.0,  5.0);
    private final Setting<Double>  breakDelay    = num("BreakDelay",     0.1, 0.0,  2.0);
    private final Setting<Boolean> antiSuicide   = bool("AntiSuicide",  true);
    private final Setting<Boolean> pauseEat      = bool("PauseEat",     true);
    private final Setting<Boolean> swing         = bool("Swing",        true);
    private final Setting<Boolean> render        = bool("Render",       true);
    private final Setting<PistonType> pistonType = mode("PistonType",   PistonType.BOTH);
    private final Setting<SpeedMode> speedMode   = mode("SpeedMode",    SpeedMode.TICK);
    private final Setting<Boolean> airPlace      = bool("AirPlace",     true);
    private final Setting<TargetPart> pushPart   = mode("PushPart",     TargetPart.HEAD);

    // ─── State ───────────────────────────────────────────────────

    private Player target;
    private Setup activeSetup;
    private Phase phase = Phase.IDLE;
    private long lastPlaceMs;
    private long phaseStartMs;

    // ─── Enums ───────────────────────────────────────────────────

    private enum PistonType { NORMAL, STICKY, BOTH }
    private enum SpeedMode { TICK, SEQUENTIAL }
    private enum TargetPart { FEET, BODY, HEAD, ANY }

    private enum Phase {
        IDLE,
        PLACE_OBSIDIAN,
        PLACE_PISTON,
        PLACE_CRYSTAL,
        PLACE_REDSTONE,
        WAIT_PUSH
    }

    private record Setup(
            BlockPos pistonPos,
            Direction pistonFacing,
            BlockPos crystalBase,
            BlockPos crystalPos,
            BlockPos redstonePos,
            Vec3 explosionPos,
            boolean placeObsidian
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
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tick
    // ═══════════════════════════════════════════════════════════════

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;
        if (pauseEat.getValue() && mc.player.isUsingItem()) return;

        if (phase == Phase.IDLE) {
            if (!placeCooldownReady()) return;

            target = findTarget();
            if (target == null) return;

            Setup setup = findBestSetup(target);
            if (setup == null) return;

            activeSetup = setup;
            phaseStartMs = System.currentTimeMillis();

            if (speedMode.getValue() == SpeedMode.TICK) {
                if (doPlacements(setup)) {
                    phase = Phase.WAIT_PUSH;
                } else {
                    resetState();
                }
            } else {
                phase = setup.placeObsidian ? Phase.PLACE_OBSIDIAN : Phase.PLACE_PISTON;
            }
        }

        if (speedMode.getValue() == SpeedMode.SEQUENTIAL && (phase == Phase.PLACE_OBSIDIAN || phase == Phase.PLACE_PISTON || phase == Phase.PLACE_CRYSTAL || phase == Phase.PLACE_REDSTONE)) {
            tickSequential();
        }

        if (phase == Phase.WAIT_PUSH) {
            tickWaitPush();
        }
    }

    // ─── Action Helpers ──────────────────────────────────────────

    private void rotateAndPlace(float yaw, float pitch, int priority, Runnable action) {
        Swedenhack.rotationManager.submit(new RotationRequest(
                ROTATION_ID, priority, yaw, pitch, RotationRequest.Mode.SILENT));
        action.run();
    }

    private void placeBlockWithSlot(BlockPos neighborPos, Direction face, int slot, float yaw, float pitch, int priority) {
        rotateAndPlace(yaw, pitch, priority, () -> {
            var conn = mc.getConnection();
            if (conn == null) return;

            int originalSlot = InventoryUtil.selected();
            boolean needSlotSwap = slot != originalSlot;

            if (needSlotSwap) {
                conn.send(new ServerboundSetCarriedItemPacket(slot));
            }

            Vec3 hitVec = Vec3.atCenterOf(neighborPos).relative(face, 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, face, neighborPos, false);

            try (var handler = ((ClientLevelAccessor) mc.level).swedenhack$getBlockStatePredictionHandler().startPredicting()) {
                conn.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, handler.currentSequence()));
            }

            if (swing.getValue()) {
                conn.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            }

            if (needSlotSwap) {
                conn.send(new ServerboundSetCarriedItemPacket(originalSlot));
            }
        });
    }

    private void placeCrystalWithSlot(BlockPos base, int slot, float yaw, float pitch, int priority) {
        rotateAndPlace(yaw, pitch, priority, () -> {
            Swedenhack.placementManager.placeCrystal(base, slot, true);
            if (swing.getValue()) {
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            }
        });
    }

    private boolean doPlacements(Setup setup) {
        int normalSlot = findHotbarItem(Items.PISTON);
        int stickySlot = findHotbarItem(Items.STICKY_PISTON);
        int pistonSlot = (pistonType.getValue() != PistonType.STICKY && normalSlot != -1) ? normalSlot : stickySlot;

        int crystalSlot = findHotbarItem(Items.END_CRYSTAL);

        int torchSlot = findHotbarItem(Items.REDSTONE_TORCH);
        int blockSlot = findHotbarItem(Items.REDSTONE_BLOCK);
        int redstoneSlot = torchSlot != -1 ? torchSlot : blockSlot;

        if (pistonSlot == -1 || crystalSlot == -1 || redstoneSlot == -1) {
            return false;
        }

        // 0. Obsidian Base
        if (setup.placeObsidian) {
            BlockState baseState = mc.level.getBlockState(setup.crystalBase);
            if (!isCrystalBaseBlock(baseState)) {
                int obsidianSlot = findHotbarItem(Items.OBSIDIAN);
                if (obsidianSlot == -1) return false;
                PlaceInfo obsidianPlace = findSolidFace(setup.crystalBase);
                if (obsidianPlace == null) return false;
                float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(setup.crystalBase));
                placeBlockWithSlot(obsidianPlace.pos, obsidianPlace.face, obsidianSlot, angles[0], angles[1], 79);
            }
        }

        // 1. Piston
        BlockState pistonState = mc.level.getBlockState(setup.pistonPos);
        if (!isPiston(pistonState)) {
            PlaceInfo pistonPlace = findPlaceInfo(setup.pistonPos);
            if (pistonPlace == null) return false;

            float requiredYaw = setup.pistonFacing.getOpposite().toYRot();
            float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(setup.pistonPos));
            float yaw = angles[0];
            float diff = Mth.wrapDegrees(requiredYaw - yaw);
            if (Math.abs(diff) > 40.0f) {
                yaw = requiredYaw + Mth.clamp(diff, -40.0f, 40.0f);
            }
            float pitch = Math.max(-40f, Math.min(40f, angles[1]));

            placeBlockWithSlot(pistonPlace.pos, pistonPlace.face, pistonSlot, yaw, pitch, 80);
        }

        // 2. Redstone
        BlockState redstoneState = mc.level.getBlockState(setup.redstonePos);
        if (!isRedstoneSource(redstoneState)) {
            PlaceInfo redstonePlace = findPlaceInfoForRedstone(setup.redstonePos, setup.pistonPos);
            if (redstonePlace == null) return false;

            float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(setup.redstonePos));
            placeBlockWithSlot(redstonePlace.pos, redstonePlace.face, redstoneSlot, angles[0], angles[1], 82);
        }

        // 3. Crystal
        AABB crystalBox = new AABB(setup.crystalPos).inflate(0.5);
        boolean hasCrystal = !mc.level.getEntitiesOfClass(EndCrystal.class, crystalBox).isEmpty();
        if (!hasCrystal) {
            float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(setup.crystalBase).add(0, 0.5, 0));
            placeCrystalWithSlot(setup.crystalBase, crystalSlot, angles[0], angles[1], 81);
        }

        return true;
    }

    private void tickSequential() {
        if (target == null || activeSetup == null || isSetupInvalid()) {
            resetState();
            return;
        }

        int normalSlot = findHotbarItem(Items.PISTON);
        int stickySlot = findHotbarItem(Items.STICKY_PISTON);
        int pistonSlot = (pistonType.getValue() != PistonType.STICKY && normalSlot != -1) ? normalSlot : stickySlot;

        int crystalSlot = findHotbarItem(Items.END_CRYSTAL);

        int torchSlot = findHotbarItem(Items.REDSTONE_TORCH);
        int blockSlot = findHotbarItem(Items.REDSTONE_BLOCK);
        int redstoneSlot = torchSlot != -1 ? torchSlot : blockSlot;

        if (pistonSlot == -1 || crystalSlot == -1 || redstoneSlot == -1) {
            resetState();
            return;
        }

        switch (phase) {
            case PLACE_OBSIDIAN -> {
                if (activeSetup.placeObsidian) {
                    BlockState baseState = mc.level.getBlockState(activeSetup.crystalBase);
                    if (!isCrystalBaseBlock(baseState)) {
                        int obsidianSlot = findHotbarItem(Items.OBSIDIAN);
                        if (obsidianSlot == -1) {
                            resetState();
                            return;
                        }
                        PlaceInfo obsidianPlace = findSolidFace(activeSetup.crystalBase);
                        if (obsidianPlace == null) {
                            resetState();
                            return;
                        }
                        float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(activeSetup.crystalBase));
                        placeBlockWithSlot(obsidianPlace.pos, obsidianPlace.face, obsidianSlot, angles[0], angles[1], 79);
                    }
                }
                phase = Phase.PLACE_PISTON;
            }
            case PLACE_PISTON -> {
                BlockState pistonState = mc.level.getBlockState(activeSetup.pistonPos);
                if (!isPiston(pistonState)) {
                    PlaceInfo pistonPlace = findPlaceInfo(activeSetup.pistonPos);
                    if (pistonPlace == null) {
                        resetState();
                        return;
                    }

                    float requiredYaw = activeSetup.pistonFacing.getOpposite().toYRot();
                    float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(activeSetup.pistonPos));
                    float yaw = angles[0];
                    float diff = Mth.wrapDegrees(requiredYaw - yaw);
                    if (Math.abs(diff) > 40.0f) {
                        yaw = requiredYaw + Mth.clamp(diff, -40.0f, 40.0f);
                    }
                    float pitch = Math.max(-40f, Math.min(40f, angles[1]));

                    placeBlockWithSlot(pistonPlace.pos, pistonPlace.face, pistonSlot, yaw, pitch, 80);
                }
                phase = Phase.PLACE_CRYSTAL;
            }
            case PLACE_CRYSTAL -> {
                AABB crystalBox = new AABB(activeSetup.crystalPos).inflate(0.5);
                boolean hasCrystal = !mc.level.getEntitiesOfClass(EndCrystal.class, crystalBox).isEmpty();
                if (!hasCrystal) {
                    float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(activeSetup.crystalBase).add(0, 0.5, 0));
                    placeCrystalWithSlot(activeSetup.crystalBase, crystalSlot, angles[0], angles[1], 81);
                }
                phase = Phase.PLACE_REDSTONE;
            }
            case PLACE_REDSTONE -> {
                BlockState redstoneState = mc.level.getBlockState(activeSetup.redstonePos);
                if (!isRedstoneSource(redstoneState)) {
                    PlaceInfo redstonePlace = findPlaceInfoForRedstone(activeSetup.redstonePos, activeSetup.pistonPos);
                    if (redstonePlace == null) {
                        resetState();
                        return;
                    }

                    float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), Vec3.atCenterOf(activeSetup.redstonePos));
                    placeBlockWithSlot(redstonePlace.pos, redstonePlace.face, redstoneSlot, angles[0], angles[1], 82);
                }
                phase = Phase.WAIT_PUSH;
                phaseStartMs = System.currentTimeMillis();
            }
            default -> {
            }
        }
    }

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
            EndCrystal crystal = crystals.getFirst();
            
            // Check if crystal has been pushed close to target/destination pos
            Vec3 destCenter = Vec3.atCenterOf(pushedPos);
            double distToDestSq = crystal.position().distanceToSqr(destCenter.x, crystal.getY(), destCenter.z);

            long elapsed = System.currentTimeMillis() - phaseStartMs;
            // Wait at least breakDelay, and ensure crystal is horizontally near destination or timeout of 150ms
            if (elapsed >= (long) (breakDelay.getValue() * 1000) && (distToDestSq < 0.25 || elapsed >= 150)) {
                attackCrystal(crystal);
                lastPlaceMs = System.currentTimeMillis();
                resetState();
            }
            return;
        }

        // Timeout
        if (System.currentTimeMillis() - phaseStartMs > 1000) {
            resetState();
        }
    }

    private void attackCrystal(EndCrystal crystal) {
        if (mc.player == null || mc.gameMode == null) return;

        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 hit = Vec3.atCenterOf(crystal.blockPosition());
        float[] angles = MathUtil.calcAngle(eyePos, hit);
        Swedenhack.rotationManager.submit(new RotationRequest(
                ROTATION_ID, 85, angles[0], angles[1], RotationRequest.Mode.SILENT));

        mc.gameMode.attack(mc.player, crystal);
        if (swing.getValue()) {
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Setup calculation
    // ═══════════════════════════════════════════════════════════════

    private Setup findBestSetup(Player target) {
        Vec3 playerEyes = mc.player.getEyePosition();
        BlockPos targetFeet = target.blockPosition();

        Setup bestSetup = null;
        float bestTargetDmg = 0f;

        int obsidianSlot = findHotbarItem(Items.OBSIDIAN);

        // For each horizontal direction, try piston setups that push crystal toward target
        for (Direction pushDir : Direction.Plane.HORIZONTAL) {
            float requiredYaw = pushDir.getOpposite().toYRot();

            for (BlockPos crystalBase : getCandidateCrystalBases(targetFeet, pushDir)) {
                BlockPos crystalPos = crystalBase.above();
                BlockPos pistonPos = crystalPos.relative(pushDir.getOpposite());

                BlockPos pushedCrystalPos = crystalPos.relative(pushDir);
                Vec3 explosionPos = Vec3.atBottomCenterOf(pushedCrystalPos).add(0, 1, 0);

                if (playerEyes.distanceToSqr(Vec3.atCenterOf(pistonPos)) > placeRange.getValue() * placeRange.getValue()) continue;
                if (playerEyes.distanceToSqr(Vec3.atCenterOf(crystalBase)) > placeRange.getValue() * placeRange.getValue()) continue;

                // Check player orientation to piston to ensure correct facing placement
                float[] anglesPiston = MathUtil.calcAngle(playerEyes, Vec3.atCenterOf(pistonPos));
                double diff = Math.abs(Mth.wrapDegrees(requiredYaw - anglesPiston[0]));
                if (diff > 80.0) continue;

                BlockPos redstonePos = findRedstonePos(pistonPos, pushDir);
                if (redstonePos == null) continue;
                if (playerEyes.distanceToSqr(Vec3.atCenterOf(redstonePos)) > placeRange.getValue() * placeRange.getValue()) continue;

                if (!canUsePistonPos(pistonPos)) continue;
                if (!canUseCrystalBase(crystalBase, crystalPos, obsidianSlot)) continue;
                if (!canUseRedstonePos(redstonePos, pistonPos)) continue;

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

                float targetDmg = DamageUtil.crystalDamage(target, explosionPos);
                if (targetDmg < minDamage.getValue()) continue;

                if (targetDmg > bestTargetDmg) {
                    bestTargetDmg = targetDmg;
                    boolean placeObsidian = !isCrystalBaseBlock(mc.level.getBlockState(crystalBase));
                    bestSetup = new Setup(pistonPos, pushDir, crystalBase, crystalPos, redstonePos, explosionPos, placeObsidian);
                }
            }
        }

        return bestSetup;
    }

    private List<BlockPos> getCandidateCrystalBases(BlockPos targetFeet, Direction pushDir) {
        List<BlockPos> bases = new ArrayList<>();
        BlockPos col = targetFeet.relative(pushDir.getOpposite());
        
        TargetPart part = pushPart.getValue();
        if (part == TargetPart.ANY) {
            for (int dy = -2; dy <= 1; dy++) {
                bases.add(col.offset(0, dy, 0));
            }
        } else if (part == TargetPart.HEAD) {
            bases.add(col.offset(0, 1, 0));
        } else if (part == TargetPart.BODY) {
            bases.add(col.offset(0, 0, 0));
        } else if (part == TargetPart.FEET) {
            bases.add(col.offset(0, -1, 0));
        }
        return bases;
    }

    private BlockPos findRedstonePos(BlockPos pistonPos, Direction pistonFacing) {
        // 1. Prefer underneath the piston to avoid airplace (click bottom face of piston)
        BlockPos belowPos = pistonPos.below();
        if (canUseRedstonePos(belowPos, pistonPos)) {
            return belowPos;
        }

        // 2. Prefer behind the piston
        BlockPos behindPos = pistonPos.relative(pistonFacing.getOpposite());
        if (canUseRedstonePos(behindPos, pistonPos) && !behindPos.equals(pistonPos.relative(pistonFacing))) {
            return behindPos;
        }

        // 3. Try other adjacent faces
        for (Direction dir : Direction.values()) {
            if (dir == pistonFacing) continue;
            BlockPos side = pistonPos.relative(dir);
            if (canUseRedstonePos(side, pistonPos)) return side;
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
        return findPlaceInfo(pos) != null;
    }

    private boolean canUseCrystalBase(BlockPos base, BlockPos crystalPos, int obsidianSlot) {
        BlockState baseState = mc.level.getBlockState(base);
        if (!isCrystalBaseBlock(baseState)) {
            if (obsidianSlot == -1) return false;
            if (!baseState.isAir() && !baseState.canBeReplaced()) return false;
            if (findPlaceInfo(base) == null) return false;
        }

        BlockState state = mc.level.getBlockState(crystalPos);
        if (!state.isAir() && !state.canBeReplaced()) return false;
        BlockState stateAbove = mc.level.getBlockState(crystalPos.above());
        if (!stateAbove.isAir() && !stateAbove.canBeReplaced()) return false;

        AABB box = new AABB(crystalPos.getX(), crystalPos.getY(), crystalPos.getZ(),
                crystalPos.getX() + 1, crystalPos.getY() + 2, crystalPos.getZ() + 1);
        return mc.level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player) && !(e instanceof EndCrystal) && e.isAlive()).isEmpty();
    }

    private boolean canUseRedstonePos(BlockPos pos, BlockPos pistonPos) {
        BlockState state = mc.level.getBlockState(pos);
        if (isRedstoneSource(state)) return true;
        if (!state.canBeReplaced()) return false;
        if (mc.player.getBoundingBox().intersects(new AABB(pos))) return false;
        if (!PlaceUtil.noEntityCollision(pos)) return false;
        
        if (findPlaceInfo(pos) != null) return true;
        for (Direction dir : Direction.values()) {
            if (pos.relative(dir).equals(pistonPos)) return true;
        }
        return false;
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
        if (eyes.distanceToSqr(Vec3.atCenterOf(activeSetup.pistonPos)) > (placeRange.getValue() + 1.0) * (placeRange.getValue() + 1.0)) return true;
        if (target.position().distanceToSqr(activeSetup.explosionPos) > 4.0) return true;
        return false;
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

    private PlaceInfo findPlaceInfo(BlockPos target) {
        PlaceInfo solid = findSolidFace(target);
        if (solid != null) return solid;
        if (airPlace.getValue()) {
            return new PlaceInfo(target, Direction.UP);
        }
        return null;
    }

    private PlaceInfo findPlaceInfoForRedstone(BlockPos redstonePos, BlockPos pistonPos) {
        PlaceInfo solid = findSolidFaceForRedstone(redstonePos, pistonPos);
        if (solid != null) return solid;
        if (airPlace.getValue()) {
            return new PlaceInfo(redstonePos, Direction.UP);
        }
        return null;
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

    private PlaceInfo findSolidFaceForRedstone(BlockPos redstonePos, BlockPos pistonPos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = redstonePos.relative(dir);
            BlockState state = mc.level.getBlockState(neighbor);
            if (!state.isAir() && !state.canBeReplaced()) {
                return new PlaceInfo(neighbor, dir.getOpposite());
            }
        }
        for (Direction dir : Direction.values()) {
            if (redstonePos.relative(dir).equals(pistonPos)) {
                return new PlaceInfo(pistonPos, dir.getOpposite());
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
