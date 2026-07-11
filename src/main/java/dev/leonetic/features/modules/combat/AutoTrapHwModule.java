package dev.leonetic.features.modules.combat;

import dev.leonetic.Swedenhack;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.TargetsModule;
import dev.leonetic.features.modules.render.LogoutSpotsModule;
import dev.leonetic.features.modules.world.SpeedMineModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.PlacementManager;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.inventory.ResultType;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Advanced movement-prediction & Elytra-interception AutoTrap.
 * Adapts features from Hackware AutoTrap to Swedenhack APIs.
 */
public class AutoTrapHwModule extends Module {

    // ── Constants ────────────────────────────────────────────────
    private static final double TERMINAL_VELOCITY = -3.92;
    private static final double DRAG_XZ_FLY = 0.99;
    private static final double DRAG_Y_FLY = 0.98;
    private static final double ALIGN_D = 1.5;
    private static final double ALIGN_E = 0.01;
    private static final double LOOK_PUSH = 0.1;
    private static final int BOOST_DURATION_TICKS = 40;
    private static final double ELYTRA_GRAVITY = -0.04;
    private static final int ELYTRA_MIN_LEAD_TICKS = 1;
    private static final int ELYTRA_MAX_LEAD_TICKS = 9;
    private static final int ELYTRA_MIN_BLOCK_BUDGET = 3;
    private static final int ELYTRA_MAX_BLOCK_BUDGET = 10;
    private static final double HITBOX_EPSILON = 1.0E-4;
    private static final double MOVE_EPSILON = 1.0E-5;
    private static final double PLAYER_WIDTH = 0.6;
    private static final double STANDING_HEIGHT = 1.8;
    private static final double CROUCHING_HEIGHT = 1.5;
    private static final double LOW_PROFILE_HEIGHT = 0.6;

    // ── Settings ─────────────────────────────────────────────────
    public enum BlockType {
        OBSIDIAN(Items.OBSIDIAN),
        COBWEB(Items.COBWEB),
        HYBRID(null);

        final Item item;
        BlockType(Item item) { this.item = item; }
    }

    private final Setting<BlockType> block = mode("Block", BlockType.OBSIDIAN).setPage("General");
    private final Setting<Boolean> pauseEat = bool("Pause Eat", true).setPage("General");
    private final Setting<Boolean> trapLogs = bool("Trap Logs", false).setPage("General");
    private final Setting<Boolean> ignoreCrawled = bool("Ignore Crawled", false).setPage("General");
    private final Setting<Boolean> predictMovement = bool("Predict Movement", true).setPage("General");
    private final Setting<Integer> predictionTicks = num("Prediction Ticks", 2, 0, 5).setPage("General").setVisibility(v -> predictMovement.getValue());

    private final Setting<Boolean> render = bool("Render", true).setPage("Render");
    private final Setting<RenderShape> shapeMode = mode("Shape Mode", RenderShape.BOTH).setPage("Render").setVisibility(v -> render.getValue());
    private final Setting<Boolean> debugPrediction = bool("Debug Prediction", false).setPage("Render").setVisibility(v -> render.getValue());
    private final Setting<Float> lineWidth = num("Line Width", 1.5f, 0.5f, 5.0f).setPage("Render").setVisibility(v -> render.getValue());
    private final Setting<Float> fadeTime = num("Fade Time", 1.0f, 0.05f, 5.0f).setPage("Render").setVisibility(v -> render.getValue());
    private final Setting<Color> fillColor = color("Fill Color", 255, 50, 50, 40).setPage("Render").setVisibility(v -> render.getValue());
    private final Setting<Color> outlineColor = color("Outline Color", 255, 50, 50, 255).setPage("Render").setVisibility(v -> render.getValue());

    // ── State ────────────────────────────────────────────────────
    private boolean buildInterceptNext = true;
    private final Set<BlockPos> wantedPoses = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> ownedQueued = new HashSet<>();
    private final Map<UUID, Integer> boostingTicks = new HashMap<>();

    private BlockPos lockedCenter = null;
    private Direction.Axis lockedPrimary = null;
    private Direction.Axis lockedSecondary = null;
    private Direction.Axis lockedTertiary = null;
    private int lockedPrimaryDir = 0;
    private int lockedSecondaryDir = 0;
    private int lockedTertiaryDir = 0;
    private int trapClosingTicks = -1;

    private UUID lockedTargetUUID = null;
    private int lastElytraLeadTicks = ELYTRA_MIN_LEAD_TICKS;
    private int lastElytraBlockBudget = ELYTRA_MIN_BLOCK_BUDGET;

    private Player target;
    private int cachedSlot = -1;
    private long lastPlaceTime = 0L;

    private final List<BlockPos> posesToRender = new ArrayList<>();
    private final Map<BlockPos, Long> renderLastPlacedBlock = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> renderLastRemovedBlock = new ConcurrentHashMap<>();
    private final Set<BlockPos> lastRenderPoses = new HashSet<>();
    private final List<Vec3> debugElytraPath = new ArrayList<>();
    private AABB debugElytraBox = null;

    private enum TrapPose {
        STANDING,
        CROUCHING,
        CRAWLING,
        SWIMMING,
        FALL_FLYING
    }

    private record PredictedTargetState(TrapPose pose, Vec3 startPos, Vec3 pos, Vec3 velocity,
                                        AABB box, Vec3 predictedTravel, int leadTicks,
                                        double uncertainty, List<Vec3> path) {}

    private record CollisionMove(Vec3 movement, AABB box, boolean collidedX,
                                 boolean collidedY, boolean collidedZ) {}

    private record ElytraSampleScore(double score, int placeableCount, int frontCount, double bestReachSq) {}

    public enum RenderShape {
        BOTH,
        LINES,
        SIDES
    }

    private final PlacementManager.PlacementListener airRefillListener = (pos, nowAir) -> {
        ownedQueued.remove(pos);
        if (!nowAir || !wantedPoses.contains(pos)) return;
        int slot = cachedSlot;
        if (slot < 0 || !PlaceUtil.canPlace(pos)) return;
        if (Swedenhack.placementManager.enqueue(pos, slot)) {
            ownedQueued.add(pos);
        }
    };

    public AutoTrapHwModule() {
        super("AutoTrapHw", "Traps targets using advanced collision-aware motion prediction and adaptive elytra interception.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        Swedenhack.placementManager.addListener(airRefillListener);
        target = null;
        posesToRender.clear();
        renderLastPlacedBlock.clear();
        renderLastRemovedBlock.clear();
        lastRenderPoses.clear();
        clearDebugPrediction();
        resetElytraTrap();
        lastPlaceTime = System.currentTimeMillis();
    }

    @Override
    public void onDisable() {
        Swedenhack.placementManager.removeListener(airRefillListener);
        Swedenhack.placementManager.removeQueuedFor(ownedQueued::contains);
        ownedQueued.clear();
        wantedPoses.clear();
        posesToRender.clear();
        renderLastPlacedBlock.clear();
        renderLastRemovedBlock.clear();
        lastRenderPoses.clear();
        clearDebugPrediction();
        resetElytraTrap();
        target = null;
        cachedSlot = -1;
    }

    private void resetElytraTrap() {
        buildInterceptNext = true;
        lockedCenter = null;
        lockedPrimary = null;
        lockedSecondary = null;
        lockedTertiary = null;
        lockedPrimaryDir = 0;
        lockedSecondaryDir = 0;
        lockedTertiaryDir = 0;
        trapClosingTicks = -1;
        lockedTargetUUID = null;
        lastElytraLeadTicks = ELYTRA_MIN_LEAD_TICKS;
        lastElytraBlockBudget = ELYTRA_MIN_BLOCK_BUDGET;
        clearDebugPrediction();
    }

    @Subscribe(priority = 1)
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundSoundPacket pkt)) return;
        if (pkt.getSound().value() != SoundEvents.FIREWORK_ROCKET_LAUNCH) return;
        double sx = pkt.getX(), sy = pkt.getY(), sz = pkt.getZ();
        mc.execute(() -> {
            if (nullCheck()) return;
            for (Player p : mc.level.players()) {
                double dx = p.getX() - sx, dy = p.getY() - sy, dz = p.getZ() - sz;
                if (dx * dx + dy * dy + dz * dz < 9.0) {
                    boostingTicks.put(p.getUUID(), BOOST_DURATION_TICKS);
                    break;
                }
            }
        });
    }

    @Subscribe(priority = 1)
    private void onTick(TickEvent event) {
        if (nullCheck()) return;

        boostingTicks.entrySet().removeIf(e -> {
            int next = e.getValue() - 1;
            e.setValue(next);
            return next <= 0;
        });

        if (trapClosingTicks > 0) {
            trapClosingTicks--;
        }

        int slot = resolveSlot();
        if (slot < 0) {
            cachedSlot = -1;
            wantedPoses.clear();
            pruneAll();
            return;
        }
        cachedSlot = slot;

        if (target == null || isBadTarget(target, 14.0) || isIgnoredCrawlingTarget(target)) {
            target = findTrapTarget();
            resetElytraTrap();
            if (target == null) {
                if (trapLogs.getValue()) {
                    target = findLogoutTarget();
                }
                if (target == null) {
                    pruneAll();
                    updateFading(Collections.emptyList());
                    return;
                }
            }
        }

        if (pauseEat.getValue() && mc.player.isUsingItem()) {
            pruneAll();
            updateFading(Collections.emptyList());
            return;
        }

        // Defer for eating just like surround
        dev.leonetic.features.modules.combat.OffhandModule offhand = Swedenhack.moduleManager.getModuleByClass(dev.leonetic.features.modules.combat.OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) {
            pruneAll();
            updateFading(Collections.emptyList());
            return;
        }

        if (lockedTargetUUID != null && !lockedTargetUUID.equals(target.getUUID())) {
            resetElytraTrap();
        }

        if (!target.isFallFlying() && lockedTargetUUID != null) {
            resetElytraTrap();
        }

        if (target.isFallFlying() && !buildInterceptNext && System.currentTimeMillis() - lastPlaceTime > 4000) {
            resetElytraTrap();
        }

        PredictedTargetState predictedState = predictTargetState(target);
        Set<BlockPos> placePoses = getPoseTrapPoses(predictedState);

        wantedPoses.clear();
        wantedPoses.addAll(placePoses);

        List<BlockPos> reachablePoses = new ArrayList<>();
        Vec3 eyePos = mc.player.getEyePosition();
        for (BlockPos pos : placePoses) {
            Vec3 closest = clampClosestPoint(eyePos, new AABB(pos));
            if (eyePos.distanceTo(closest) <= 5.1) {
                reachablePoses.add(pos.immutable());
            }
        }

        if (reachablePoses.isEmpty()) {
            pruneOwned();
            updateFading(Collections.emptyList());
            return;
        }

        List<BlockPos> renderablePoses = new ArrayList<>();
        for (BlockPos pos : reachablePoses) {
            BlockState state = mc.level.getBlockState(pos);
            if (state.canBeReplaced()) {
                renderablePoses.add(pos);
            }
        }

        SpeedMineModule mine = Swedenhack.moduleManager.getModuleByClass(SpeedMineModule.class);
        if (mine != null && mine.isEnabled()) {
            renderablePoses.removeIf(mine::alreadyBreaking);
        }

        sortTrapCandidates(renderablePoses, predictedState, eyePos);

        if (predictedState.pose == TrapPose.FALL_FLYING) {
            int maxStopBlocks = Math.min(lastElytraBlockBudget, renderablePoses.size());
            if (maxStopBlocks > 0 && renderablePoses.size() > maxStopBlocks) {
                renderablePoses = new ArrayList<>(renderablePoses.subList(0, maxStopBlocks));
            }
        }

        posesToRender.clear();
        posesToRender.addAll(renderablePoses);

        pruneOwned();

        long now = System.currentTimeMillis();
        boolean placedAny = false;
        List<BlockPos> placedBlocks = new ArrayList<>();
        for (BlockPos pos : renderablePoses) {
            if (Swedenhack.placementManager.enqueue(pos, cachedSlot)) {
                ownedQueued.add(pos);
                placedBlocks.add(pos);
                placedAny = true;
            }
        }

        if (placedAny) {
            lastPlaceTime = now;
        }

        if (!placedBlocks.isEmpty() && target != null && target.isFallFlying()) {
            if (buildInterceptNext) {
                buildInterceptNext = false;
                trapClosingTicks = Math.max(lastElytraLeadTicks - 1, 0);
            }
        }

        if (!placedBlocks.isEmpty()) {
            posesToRender.removeAll(placedBlocks);
        }

        updateFading(posesToRender);
    }

    private void updateFading(List<BlockPos> currentPoses) {
        long now = System.currentTimeMillis();
        double fadeMs = fadeTime.getValue() * 1000.0;

        Set<BlockPos> currentSet = new HashSet<>(currentPoses);

        for (BlockPos pos : currentSet) {
            renderLastPlacedBlock.put(pos, now);
        }

        for (BlockPos pos : lastRenderPoses) {
            if (!currentSet.contains(pos)) {
                renderLastRemovedBlock.put(pos, now);
            }
        }

        lastRenderPoses.clear();
        lastRenderPoses.addAll(currentSet);

        if (fadeMs > 0) {
            renderLastPlacedBlock.entrySet().removeIf(e -> now - e.getValue() > fadeMs);
            renderLastRemovedBlock.entrySet().removeIf(e -> now - e.getValue() > fadeMs);
        } else {
            renderLastPlacedBlock.entrySet().removeIf(e -> !currentSet.contains(e.getKey()));
            renderLastRemovedBlock.clear();
        }
    }

    private int resolveSlot() {
        if (block.getValue() == BlockType.HYBRID) {
            int web = hotbarSlotOf(Items.COBWEB);
            return web >= 0 ? web : hotbarSlotOf(Items.OBSIDIAN);
        }
        return hotbarSlotOf(block.getValue().item);
    }

    private int hotbarSlotOf(Item item) {
        var r = InventoryUtil.find(item, InventoryUtil.HOTBAR_SCOPE);
        return (r.found() && r.type() != ResultType.OFFHAND) ? r.slot() : -1;
    }

    private PredictedTargetState predictTargetState(Player player) {
        TrapPose pose = detectTrapPose(player);
        Vec3 startPos = player.position();
        Vec3 pos = startPos;
        Vec3 velocity = player.getDeltaMovement();
        AABB box = getPoseBoxAt(player, pose, pos);

        if (pose == TrapPose.FALL_FLYING && predictMovement.getValue()) {
            return predictElytraTargetState(player, pose, startPos, box, velocity);
        }

        clearDebugPrediction();
        boolean onGround = player.onGround();

        int ticks = getPredictionTicksFor(pose);
        for (int i = 0; i < ticks; i++) {
            Vec3 desiredMove = velocity;
            if (desiredMove.lengthSqr() <= MOVE_EPSILON * MOVE_EPSILON) break;

            CollisionMove collisionMove = moveWithCollision(player, box, desiredMove);
            box = collisionMove.box;
            pos = pos.add(collisionMove.movement);

            boolean grounded = (collisionMove.collidedY && desiredMove.y < 0.0) || isBoxOnGround(player, box);
            velocity = nextVelocity(player, pose, desiredMove, collisionMove.movement, grounded);
            onGround = grounded;
        }

        Vec3 predictedTravel = pos.subtract(startPos);
        if (horizontalLengthSqr(predictedTravel) <= MOVE_EPSILON * MOVE_EPSILON) {
            predictedTravel = player.getDeltaMovement();
        }

        return new PredictedTargetState(pose, startPos, pos, velocity, box, predictedTravel, ticks, 0.0, List.of(startPos, pos));
    }

    private TrapPose detectTrapPose(Player player) {
        if (player.isFallFlying()) return TrapPose.FALL_FLYING;
        if (player.isSwimming()) return TrapPose.SWIMMING;
        if (player.isVisuallyCrawling()) return TrapPose.CRAWLING;
        if (player.isCrouching()) return TrapPose.CROUCHING;
        return TrapPose.STANDING;
    }

    private int getPredictionTicksFor(TrapPose pose) {
        if (!predictMovement.getValue()) return 0;
        return pose == TrapPose.FALL_FLYING ? 0 : predictionTicks.getValue();
    }

    private PredictedTargetState predictElytraTargetState(Player player, TrapPose pose, Vec3 startPos, AABB startBox, Vec3 startVelocity) {
        List<PredictedTargetState> samples = new ArrayList<>();
        List<Vec3> path = new ArrayList<>();
        path.add(startPos);

        Vec3 pos = startPos;
        Vec3 velocity = startVelocity;
        AABB box = startBox;
        int maxLeadTicks = getAdaptiveElytraMaxLead(player, startVelocity);

        PredictedTargetState bestState = null;
        ElytraSampleScore bestScore = null;

        for (int tick = ELYTRA_MIN_LEAD_TICKS; tick <= maxLeadTicks; tick++) {
            Vec3 desiredMove = velocity;
            if (desiredMove.lengthSqr() <= MOVE_EPSILON * MOVE_EPSILON) break;

            CollisionMove collisionMove = moveWithCollision(player, box, desiredMove);
            box = collisionMove.box;
            pos = pos.add(collisionMove.movement);
            velocity = nextVelocity(player, pose, desiredMove, collisionMove.movement, false);
            path.add(pos);

            Vec3 predictedTravel = pos.subtract(startPos);
            double uncertainty = getElytraUncertainty(player, desiredMove, velocity, tick);
            PredictedTargetState sample = new PredictedTargetState(
                pose, startPos, pos, velocity, box, predictedTravel, tick, uncertainty, List.copyOf(path));
            samples.add(sample);

            ElytraSampleScore sampleScore = scoreElytraPredictionSample(sample);
            if (bestState == null || sampleScore.score > bestScore.score) {
                bestState = sample;
                bestScore = sampleScore;
            }

            if (sampleScore.frontCount > 0 && sampleScore.placeableCount >= ELYTRA_MIN_BLOCK_BUDGET && sampleScore.score >= 160.0) {
                bestState = sample;
                bestScore = sampleScore;
                break;
            }

            if (collisionMove.collidedX || collisionMove.collidedY || collisionMove.collidedZ) break;
        }

        if (bestState == null) {
            bestState = new PredictedTargetState(
                pose, startPos, startPos, startVelocity, startBox, startVelocity,
                0, getElytraUncertainty(player, startVelocity, startVelocity, 0), List.of(startPos));
            bestScore = scoreElytraPredictionSample(bestState);
        }

        lastElytraLeadTicks = Math.max(ELYTRA_MIN_LEAD_TICKS, bestState.leadTicks);
        lastElytraBlockBudget = getAdaptiveElytraBlockBudget(bestState, bestScore);
        updateDebugPrediction(bestState);
        return bestState;
    }

    private int getAdaptiveElytraMaxLead(Player player, Vec3 velocity) {
        double speed = velocity.length();
        double horizontalSpeed = Math.sqrt(horizontalLengthSqr(velocity));
        double lookAlignment = getHorizontalAlignment(velocity, player.getLookAngle());
        int lead = 4;

        if (speed > 0.7) lead++;
        if (speed > 1.15) lead++;
        if (horizontalSpeed > 1.5) lead++;
        if (boostingTicks.containsKey(player.getUUID())) lead += 2;
        if (lookAlignment < 0.35) lead--;

        return Math.max(ELYTRA_MIN_LEAD_TICKS, Math.min(ELYTRA_MAX_LEAD_TICKS, lead));
    }

    private double getElytraUncertainty(Player player, Vec3 previousVelocity, Vec3 nextVelocity, int leadTicks) {
        double speed = previousVelocity.length();
        double lookAlignment = getHorizontalAlignment(previousVelocity, player.getLookAngle());
        double turnAmount = previousVelocity.lengthSqr() <= MOVE_EPSILON * MOVE_EPSILON || nextVelocity.lengthSqr() <= MOVE_EPSILON * MOVE_EPSILON
            ? 0.0
            : 1.0 - Math.max(-1.0, Math.min(1.0, previousVelocity.normalize().dot(nextVelocity.normalize())));

        double speedFactor = Mth.clamp(speed / 2.0, 0.0, 1.0);
        double turnFactor = Mth.clamp(turnAmount * 1.8, 0.0, 1.0);
        double steeringFactor = Mth.clamp(1.0 - Math.max(0.0, lookAlignment), 0.0, 1.0);
        double leadFactor = Mth.clamp(leadTicks / (double) ELYTRA_MAX_LEAD_TICKS, 0.0, 1.0);

        return Mth.clamp(speedFactor * 0.35 + turnFactor * 0.25 + steeringFactor * 0.25 + leadFactor * 0.15, 0.0, 1.0);
    }

    private ElytraSampleScore scoreElytraPredictionSample(PredictedTargetState sample) {
        if (mc.player == null) return new ElytraSampleScore(Double.NEGATIVE_INFINITY, 0, 0, Double.POSITIVE_INFINITY);

        Vec3 eye = mc.player.getEyePosition();
        Vec3 forward = horizontalUnit(sample.predictedTravel);
        Vec3 center = boxCenter(sample.box);
        int placeable = 0;
        int front = 0;
        double bestReachSq = Double.POSITIVE_INFINITY;

        for (BlockPos pos : getPoseTrapPoses(sample)) {
            if (!PlaceUtil.canPlace(pos)) continue;
            Vec3 closest = clampClosestPoint(eye, new AABB(pos));
            double reachSq = eye.distanceToSqr(closest);
            if (reachSq > 5.1 * 5.1) continue;

            placeable++;
            bestReachSq = Math.min(bestReachSq, reachSq);

            if (forward.lengthSqr() > 0.0) {
                Vec3 toBlock = new Vec3(Vec3.atCenterOf(pos).x - center.x, 0.0, Vec3.atCenterOf(pos).z - center.z);
                double len = Math.sqrt(horizontalLengthSqr(toBlock));
                if (len > MOVE_EPSILON && (toBlock.x * forward.x + toBlock.z * forward.z) / len > 0.35) {
                    front++;
                }
            }
        }

        if (placeable == 0) return new ElytraSampleScore(Double.NEGATIVE_INFINITY, 0, 0, bestReachSq);

        double reach = Math.sqrt(bestReachSq);
        double reachScore = Mth.clamp((5.1 - reach) / 5.1, 0.0, 1.0) * 220.0;
        double score = reachScore
            + Math.min(placeable, ELYTRA_MAX_BLOCK_BUDGET) * 18.0
            + front * 70.0
            - sample.leadTicks * (10.0 + sample.uncertainty * 22.0)
            - sample.uncertainty * 65.0;

        return new ElytraSampleScore(score, placeable, front, bestReachSq);
    }

    private int getAdaptiveElytraBlockBudget(PredictedTargetState state, ElytraSampleScore score) {
        double speed = state.velocity.length();
        int desired = ELYTRA_MIN_BLOCK_BUDGET;
        if (score.frontCount <= 1) desired++;
        if (speed > 0.7) desired++;
        if (speed > 1.15) desired++;
        desired += (int) Math.round(state.uncertainty * 4.0);

        if (score.placeableCount > 0) desired = Math.min(desired, score.placeableCount);
        return Math.max(ELYTRA_MIN_BLOCK_BUDGET, Math.min(ELYTRA_MAX_BLOCK_BUDGET, desired));
    }

    private double getHorizontalAlignment(Vec3 velocity, Vec3 look) {
        double vLen = Math.sqrt(horizontalLengthSqr(velocity));
        double lLen = Math.sqrt(horizontalLengthSqr(look));
        if (vLen <= MOVE_EPSILON || lLen <= MOVE_EPSILON) return 1.0;
        return (velocity.x * look.x + velocity.z * look.z) / (vLen * lLen);
    }

    private void updateDebugPrediction(PredictedTargetState state) {
        debugElytraPath.clear();
        debugElytraPath.addAll(state.path);
        debugElytraBox = state.box;
    }

    private void clearDebugPrediction() {
        debugElytraPath.clear();
        debugElytraBox = null;
    }

    private AABB getPoseBoxAt(Player player, TrapPose pose, Vec3 pos) {
        double width = Math.max(PLAYER_WIDTH, player.getBbWidth());
        double height = switch (pose) {
            case FALL_FLYING, SWIMMING, CRAWLING -> LOW_PROFILE_HEIGHT;
            case CROUCHING -> CROUCHING_HEIGHT;
            case STANDING -> STANDING_HEIGHT;
        };

        return new AABB(
            pos.x - width / 2.0, pos.y, pos.z - width / 2.0,
            pos.x + width / 2.0, pos.y + height, pos.z + width / 2.0
        );
    }

    private CollisionMove moveWithCollision(Player player, AABB box, Vec3 desiredMove) {
        AABB movedBox = box;

        double dy = clipAxisMove(player, movedBox, desiredMove.y, Direction.Axis.Y);
        movedBox = movedBox.move(0.0, dy, 0.0);

        double dx = clipAxisMove(player, movedBox, desiredMove.x, Direction.Axis.X);
        movedBox = movedBox.move(dx, 0.0, 0.0);

        double dz = clipAxisMove(player, movedBox, desiredMove.z, Direction.Axis.Z);
        movedBox = movedBox.move(0.0, 0.0, dz);

        return new CollisionMove(
            new Vec3(dx, dy, dz),
            movedBox,
            Math.abs(dx - desiredMove.x) > MOVE_EPSILON,
            Math.abs(dy - desiredMove.y) > MOVE_EPSILON,
            Math.abs(dz - desiredMove.z) > MOVE_EPSILON
        );
    }

    private double clipAxisMove(Player player, AABB box, double desired, Direction.Axis axis) {
        if (Math.abs(desired) <= MOVE_EPSILON) return 0.0;
        if (canOccupy(player, moveBoxAlongAxis(box, axis, desired))) return desired;

        double best = 0.0;
        double low = 0.0;
        double high = 1.0;

        for (int i = 0; i < 12; i++) {
            double mid = (low + high) * 0.5;
            double candidate = desired * mid;
            if (canOccupy(player, moveBoxAlongAxis(box, axis, candidate))) {
                best = candidate;
                low = mid;
            } else {
                high = mid;
            }
        }

        return Math.abs(best) <= MOVE_EPSILON ? 0.0 : best;
    }

    private AABB moveBoxAlongAxis(AABB box, Direction.Axis axis, double amount) {
        return switch (axis) {
            case X -> box.move(amount, 0.0, 0.0);
            case Y -> box.move(0.0, amount, 0.0);
            case Z -> box.move(0.0, 0.0, amount);
        };
    }

    private boolean canOccupy(Player player, AABB box) {
        if (mc.level == null) return false;
        return mc.level.noCollision(player, box.deflate(HITBOX_EPSILON, HITBOX_EPSILON, HITBOX_EPSILON));
    }

    private boolean isBoxOnGround(Player player, AABB box) {
        return !canOccupy(player, box.move(0.0, -0.05, 0.0));
    }

    private Vec3 nextVelocity(Player player, TrapPose pose, Vec3 desiredMove, Vec3 actualMove, boolean onGround) {
        Vec3 clipped = new Vec3(
            Math.abs(actualMove.x - desiredMove.x) > MOVE_EPSILON ? 0.0 : actualMove.x,
            Math.abs(actualMove.y - desiredMove.y) > MOVE_EPSILON ? 0.0 : actualMove.y,
            Math.abs(actualMove.z - desiredMove.z) > MOVE_EPSILON ? 0.0 : actualMove.z
        );

        if (pose == TrapPose.FALL_FLYING) {
            return boostingTicks.containsKey(player.getUUID())
                ? nextBoostedElytraVelocity(clipped)
                : nextElytraVelocity(player, clipped);
        }

        if (player.isInWater()) {
            return new Vec3(clipped.x * 0.8, (clipped.y - 0.02) * 0.8, clipped.z * 0.8);
        }

        if (player.isInLava()) {
            return new Vec3(clipped.x * 0.5, (clipped.y - 0.02) * 0.5, clipped.z * 0.5);
        }

        double y = clipped.y;
        if (!onGround) {
            y -= 0.08;
            if (y < TERMINAL_VELOCITY) y = TERMINAL_VELOCITY;
        } else if (y < 0.0) {
            y = 0.0;
        }

        double horizontalDrag = onGround ? 0.6 : 0.91;
        return new Vec3(clipped.x * horizontalDrag, y * 0.98, clipped.z * horizontalDrag);
    }

    private Vec3 nextElytraVelocity(Player player, Vec3 velocity) {
        float pitchRad = (float) Math.toRadians(player.getXRot());
        Vec3 look = player.getLookAngle();
        double cos = Math.cos(pitchRad);
        double horizSpeed = Math.hypot(velocity.x, velocity.z);
        double len = velocity.length();
        double liftFactor = cos * cos * Math.min(1.0, len / 0.4);

        double vy = velocity.y + (ELYTRA_GRAVITY + liftFactor * 0.06);
        if (velocity.y < 0.0 && horizSpeed > 0.0) vy += (-0.1 * velocity.y) * liftFactor;

        Vec3 lifted = new Vec3(velocity.x, vy, velocity.z);
        Vec3 align = new Vec3(
            look.x * LOOK_PUSH + (look.x * ALIGN_D - lifted.x) * ALIGN_E,
            look.y * LOOK_PUSH + (look.y * ALIGN_D - lifted.y) * ALIGN_E,
            look.z * LOOK_PUSH + (look.z * ALIGN_D - lifted.z) * ALIGN_E
        );
        Vec3 aligned = lifted.add(align);
        return new Vec3(aligned.x * DRAG_XZ_FLY, aligned.y * DRAG_Y_FLY, aligned.z * DRAG_XZ_FLY);
    }

    private Vec3 nextBoostedElytraVelocity(Vec3 velocity) {
        final double boostDrag = 0.991;
        double y = Math.max(velocity.y, TERMINAL_VELOCITY);
        return new Vec3(velocity.x * boostDrag, y, velocity.z * boostDrag);
    }

    private Set<BlockPos> getPoseTrapPoses(PredictedTargetState predicted) {
        if (predicted.pose == TrapPose.FALL_FLYING) {
            return getElytraTrapPoses(target);
        } else if (predicted.pose == TrapPose.SWIMMING) {
            return getSwimmingTrapPoses(target);
        } else if (predicted.pose == TrapPose.CRAWLING) {
            return getCrawlingTrapPoses(target);
        }

        Set<BlockPos> poses = new LinkedHashSet<>();
        AABB box = predicted.box.deflate(0.025, 0.0, 0.025);

        int minX = floorMin(box.minX);
        int maxX = floorMax(box.maxX);
        int minY = floorMin(box.minY);
        int maxY = floorMax(box.maxY);
        int minZ = floorMin(box.minZ);
        int maxZ = floorMax(box.maxZ);
        int wallMinY = firstTrapWallY(predicted.pose, minY, maxY);

        for (int y = wallMinY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos occupied = new BlockPos(x, y, z);
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        addTrapCandidate(poses, occupied.relative(dir), box);
                    }
                }
            }
        }

        int ceilingY = maxY + 1;
        int floorY = minY - 1;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                addTrapCandidate(poses, new BlockPos(x, ceilingY, z), box);
                addTrapCandidate(poses, new BlockPos(x, floorY, z), box);
            }
        }

        addPrimaryForwardCandidates(poses, predicted, box, minX, maxX, minY, maxY, minZ, maxZ);

        return poses;
    }

    private int firstTrapWallY(TrapPose pose, int minY, int maxY) {
        if ((pose == TrapPose.STANDING || pose == TrapPose.CROUCHING) && minY < maxY) {
            return minY + 1;
        }
        return minY;
    }

    private void addPrimaryForwardCandidates(Set<BlockPos> poses, PredictedTargetState predicted, AABB box,
                                             int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        Direction forward = primaryHorizontalDirection(predicted.predictedTravel);
        if (forward == null) return;
        int wallMinY = firstTrapWallY(predicted.pose, minY, maxY);

        switch (forward) {
            case EAST -> {
                for (int y = wallMinY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) addTrapCandidate(poses, new BlockPos(maxX + 1, y, z), box);
            }
            case WEST -> {
                for (int y = wallMinY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) addTrapCandidate(poses, new BlockPos(minX - 1, y, z), box);
            }
            case SOUTH -> {
                for (int y = wallMinY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) addTrapCandidate(poses, new BlockPos(x, y, maxZ + 1), box);
            }
            case NORTH -> {
                for (int y = wallMinY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) addTrapCandidate(poses, new BlockPos(x, y, minZ - 1), box);
            }
            default -> {}
        }
    }

    private void addTrapCandidate(Set<BlockPos> poses, BlockPos pos, AABB targetBox) {
        if (new AABB(pos).intersects(targetBox.deflate(HITBOX_EPSILON, HITBOX_EPSILON, HITBOX_EPSILON))) return;
        poses.add(pos.immutable());
    }

    private void sortTrapCandidates(List<BlockPos> poses, PredictedTargetState predicted, Vec3 eyePos) {
        Vec3 forward = horizontalUnit(predicted.predictedTravel);
        Vec3 center = boxCenter(predicted.box);

        poses.sort(Comparator
            .<BlockPos>comparingDouble(pos -> scoreTrapCandidate(pos, predicted, center, forward)).reversed()
            .thenComparingDouble(pos -> eyePos.distanceToSqr(Vec3.atCenterOf(pos))));
    }

    private double scoreTrapCandidate(BlockPos pos, PredictedTargetState predicted, Vec3 center, Vec3 forward) {
        double score = 0.0;
        Vec3 blockCenter = Vec3.atCenterOf(pos);

        if (forward.lengthSqr() > 0.0) {
            Vec3 toBlock = new Vec3(blockCenter.x - center.x, 0.0, blockCenter.z - center.z);
            double len = Math.sqrt(horizontalLengthSqr(toBlock));
            if (len > MOVE_EPSILON) {
                double dot = (toBlock.x * forward.x + toBlock.z * forward.z) / len;
                score += dot * 120.0;
                if (dot > 0.35) score += 180.0;
                if (dot < -0.25) score -= 120.0;
            }
        }

        if (blockCenter.y >= predicted.box.minY && blockCenter.y <= predicted.box.maxY) {
            score += 45.0;
        } else if (blockCenter.y > predicted.box.maxY) {
            score += 12.0;
        } else {
            score -= 8.0;
        }

        if (predicted.pose == TrapPose.FALL_FLYING || predicted.pose == TrapPose.SWIMMING || predicted.pose == TrapPose.CRAWLING) {
            score += blockCenter.y >= predicted.box.minY && blockCenter.y <= predicted.box.maxY ? 35.0 : 0.0;
        }

        return score;
    }

    private Direction primaryHorizontalDirection(Vec3 movement) {
        if (horizontalLengthSqr(movement) <= MOVE_EPSILON * MOVE_EPSILON) return null;
        return Math.abs(movement.x) >= Math.abs(movement.z)
            ? (movement.x >= 0.0 ? Direction.EAST : Direction.WEST)
            : (movement.z >= 0.0 ? Direction.SOUTH : Direction.NORTH);
    }

    private Vec3 horizontalUnit(Vec3 movement) {
        double length = Math.sqrt(horizontalLengthSqr(movement));
        if (length <= MOVE_EPSILON) return Vec3.ZERO;
        return new Vec3(movement.x / length, 0.0, movement.z / length);
    }

    private static double horizontalLengthSqr(Vec3 vec) {
        return vec.x * vec.x + vec.z * vec.z;
    }

    private static Vec3 boxCenter(AABB box) {
        return new Vec3(
            (box.minX + box.maxX) * 0.5,
            (box.minY + box.maxY) * 0.5,
            (box.minZ + box.maxZ) * 0.5
        );
    }

    private static int floorMin(double value) {
        return Mth.floor(value + HITBOX_EPSILON);
    }

    private static int floorMax(double value) {
        return Mth.floor(value - HITBOX_EPSILON);
    }

    private Set<BlockPos> getCrawlingTrapPoses(Player player) {
        Vec3 centerPos = player.position();

        if (predictMovement.getValue() && predictionTicks.getValue() > 0) {
            Vec3 vel = player.getDeltaMovement();
            centerPos = centerPos.add(vel.scale(predictionTicks.getValue()));
        }

        Set<BlockPos> poses = new LinkedHashSet<>();

        double width = player.getBbWidth();
        double height = player.getBbHeight();
        AABB boundingBox = new AABB(
            centerPos.x - width / 2, centerPos.y, centerPos.z - width / 2,
            centerPos.x + width / 2, centerPos.y + height, centerPos.z + width / 2
        ).deflate(0.05, 0.1, 0.05);

        double feetY = centerPos.y;
        AABB feetBox = new AABB(boundingBox.minX, feetY, boundingBox.minZ, boundingBox.maxX, feetY + 0.1, boundingBox.maxZ);

        Set<BlockPos> occupiedPoses = new LinkedHashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(feetBox.minX), Mth.floor(feetBox.minY), Mth.floor(feetBox.minZ),
                Mth.floor(feetBox.maxX), Mth.floor(feetBox.maxY), Mth.floor(feetBox.maxZ))) {
            occupiedPoses.add(pos.immutable());
        }

        boolean canStandUp = false;
        SpeedMineModule speedMine = Swedenhack.moduleManager.getModuleByClass(SpeedMineModule.class);
        for (BlockPos pos : occupiedPoses) {
            BlockPos abovePos = pos.above();
            BlockState aboveState = mc.level.getBlockState(abovePos);
            if (aboveState.isAir() || aboveState.canBeReplaced()
                    || (speedMine != null && speedMine.isEnabled() && speedMine.alreadyBreaking(abovePos))) {
                canStandUp = true;
                break;
            }
        }

        for (BlockPos pos : occupiedPoses) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos adjacent = pos.relative(dir);
                if (!mc.level.getBlockState(adjacent).isAir()) continue;

                for (Direction dir2 : Direction.Plane.HORIZONTAL) {
                    BlockPos placePos = adjacent.relative(dir2);
                    if (!occupiedPoses.contains(placePos) && !placePos.equals(pos)) {
                        poses.add(placePos);
                    }
                }
            }

            poses.add(pos.above());
            BlockPos belowPos = pos.below();
            if (PlaceUtil.canPlace(belowPos)) {
                poses.add(belowPos);
            }

            if (canStandUp) {
                BlockPos standingHead = pos.above(2);
                poses.add(standingHead);

                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    poses.add(pos.above().relative(dir));
                }
            }
        }

        return poses;
    }

    private Set<BlockPos> getSwimmingTrapPoses(Player player) {
        if (mc.level == null || player == null) return new LinkedHashSet<>();

        Vec3 centerPos = player.position();
        Set<BlockPos> poses = new LinkedHashSet<>();

        double width = player.getBbWidth();
        double height = player.getBbHeight();
        AABB boundingBox = new AABB(
            centerPos.x - width / 2, centerPos.y, centerPos.z - width / 2,
            centerPos.x + width / 2, centerPos.y + height, centerPos.z + width / 2
        ).deflate(0.05, 0.1, 0.05);

        int minX = (int) Math.floor(boundingBox.minX - 1);
        int maxX = (int) Math.ceil(boundingBox.maxX);
        int minY = (int) Math.floor(boundingBox.minY - 1);
        int maxY = (int) Math.ceil(boundingBox.maxY);
        int minZ = (int) Math.floor(boundingBox.minZ - 1);
        int maxZ = (int) Math.ceil(boundingBox.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean isInside = x >= boundingBox.minX && x < boundingBox.maxX &&
                        y >= boundingBox.minY && y < boundingBox.maxY &&
                        z >= boundingBox.minZ && z < boundingBox.maxZ;

                    if (isInside) continue;

                    BlockPos pos = new BlockPos(x, y, z);
                    if (PlaceUtil.canPlace(pos)) {
                        poses.add(pos);
                    }
                }
            }
        }
        return poses;
    }

    private Set<BlockPos> getElytraTrapPoses(Player player) {
        Set<BlockPos> poses = new LinkedHashSet<>();

        if (buildInterceptNext) {
            UUID id = player.getUUID();
            int leadTicks = getAdaptiveElytraMaxLead(player, player.getDeltaMovement());
            Vec3 pred = boostingTicks.containsKey(id)
                ? simulateBoostedElytraFuturePos(player, leadTicks)
                : simulateElytraFuturePos(player, leadTicks);

            lockedCenter = BlockPos.containing(pred);
            lockedTargetUUID = player.getUUID();
            Vec3 targetVel = target.getDeltaMovement();

            Map<Direction.Axis, Double> axisStrength = Stream.of(Direction.Axis.values())
                .collect(Collectors.toMap(axis -> axis, axis -> Math.abs(getAxisComponent(targetVel, axis))));

            List<Direction.Axis> sortedAxes = axisStrength.entrySet().stream()
                .sorted(Map.Entry.<Direction.Axis, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

            lockedPrimary = sortedAxes.get(0);
            lockedSecondary = sortedAxes.get(1);
            lockedTertiary = sortedAxes.get(2);

            lockedPrimaryDir = (int) Math.signum(getAxisComponent(targetVel, lockedPrimary));
            if (lockedPrimaryDir == 0) lockedPrimaryDir = 1;

            lockedSecondaryDir = (int) Math.signum(getAxisComponent(targetVel, lockedSecondary));
            if (lockedSecondaryDir == 0) lockedSecondaryDir = 1;

            lockedTertiaryDir = (int) Math.signum(getAxisComponent(targetVel, lockedTertiary));
            if (lockedTertiaryDir == 0) lockedTertiaryDir = 1;

            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < 2; j++) {
                    poses.add(offsetAlongAxis(offsetAlongAxis(offsetAlongAxis(lockedCenter,
                        lockedPrimary, lockedPrimaryDir),
                        lockedSecondary, i * lockedSecondaryDir),
                        lockedTertiary, j * lockedTertiaryDir));
                }
            }
            for (int i = 0; i < 2; i++) {
                poses.add(offsetAlongAxis(offsetAlongAxis(lockedCenter,
                    lockedSecondary, 2 * lockedSecondaryDir),
                    lockedTertiary, i * lockedTertiaryDir));
                poses.add(offsetAlongAxis(offsetAlongAxis(lockedCenter,
                    lockedTertiary, 2 * lockedTertiaryDir),
                    lockedSecondary, i * lockedSecondaryDir));
            }

            int maxStopBlocks = lastElytraBlockBudget;
            if (poses.size() > maxStopBlocks) {
                List<BlockPos> prioritized = new ArrayList<>(poses);
                prioritized.sort(Comparator.comparingDouble(this::scoreElytraStopBlock).reversed());
                poses.clear();
                for (int i = 0; i < Math.min(maxStopBlocks, prioritized.size()); i++) {
                    poses.add(prioritized.get(i));
                }
            }
        } else {
            AABB boundingBox = player.getBoundingBox().deflate(0.01, 0.1, 0.01);
            int feetY = player.blockPosition().getY();

            int minX = (int) Math.floor(boundingBox.minX);
            int maxX = (int) Math.floor(boundingBox.maxX);
            int minZ = (int) Math.floor(boundingBox.minZ);
            int maxZ = (int) Math.floor(boundingBox.maxZ);

            for (int x = minX; x <= maxX; ++x) {
                for (int z = minZ; z <= maxZ; ++z) {
                    BlockPos feetPos = new BlockPos(x, feetY, z);

                    for (int offsetX = -1; offsetX <= 1; ++offsetX) {
                        for (int offsetZ = -1; offsetZ <= 1; ++offsetZ) {
                            if (Math.abs(offsetX) + Math.abs(offsetZ) == 1) {
                                BlockPos adjacentPos = feetPos.offset(offsetX, 0, offsetZ);
                                if (PlaceUtil.canPlace(adjacentPos)) {
                                    poses.add(adjacentPos);
                                }

                                BlockPos headPos = adjacentPos.above();
                                if (PlaceUtil.canPlace(headPos)) {
                                    poses.add(headPos);
                                }
                            }
                        }
                    }

                    BlockPos belowFeetPos = feetPos.below();
                    if (PlaceUtil.canPlace(belowFeetPos)) {
                        poses.add(belowFeetPos);
                    }

                    BlockPos aboveHeadPos = feetPos.above(2);
                    if (PlaceUtil.canPlace(aboveHeadPos)) {
                        poses.add(aboveHeadPos);
                    }
                }
            }
        }

        return poses;
    }

    private double scoreElytraStopBlock(BlockPos pos) {
        if (lockedCenter == null || lockedPrimary == null || lockedSecondary == null || lockedTertiary == null) return 0.0;

        int relPrimary = getAxisComponent(pos, lockedPrimary) - getAxisComponent(lockedCenter, lockedPrimary);
        int relSecondary = Math.abs(getAxisComponent(pos, lockedSecondary) - getAxisComponent(lockedCenter, lockedSecondary));
        int relTertiary = Math.abs(getAxisComponent(pos, lockedTertiary) - getAxisComponent(lockedCenter, lockedTertiary));

        double score = 0.0;

        if (relPrimary == lockedPrimaryDir) score += 100.0;
        else if (relPrimary == 0) score += 40.0;

        score -= relSecondary * 6.0;
        score -= relTertiary * 6.0;

        if (relSecondary >= 2 || relTertiary >= 2) score += 8.0;

        return score;
    }

    private Vec3 simulateElytraFuturePos(Player player, int ticks) {
        Vec3 pos = player.position();
        Vec3 vel = player.getDeltaMovement();

        float pitchRad = (float) Math.toRadians(player.getXRot());
        Vec3 look = player.getLookAngle();
        double cos = Math.cos(pitchRad);

        for (int i = 0; i < ticks; i++) {
            pos = pos.add(vel);

            double horizSpeed = Math.hypot(vel.x, vel.z);
            double len = vel.length();
            double liftFactor = cos * cos * Math.min(1.0, len / 0.4);

            double vy = vel.y + (ELYTRA_GRAVITY + liftFactor * 0.06);

            if (vel.y < 0.0 && horizSpeed > 0.0) vy += (-0.1 * vel.y) * liftFactor;

            Vec3 vAfterLift = new Vec3(vel.x, vy, vel.z);

            Vec3 align = new Vec3(
                look.x * LOOK_PUSH + (look.x * ALIGN_D - vAfterLift.x) * ALIGN_E,
                look.y * LOOK_PUSH + (look.y * ALIGN_D - vAfterLift.y) * ALIGN_E,
                look.z * LOOK_PUSH + (look.z * ALIGN_D - vAfterLift.z) * ALIGN_E
            );
            Vec3 vAligned = vAfterLift.add(align);

            vel = new Vec3(vAligned.x * DRAG_XZ_FLY, vAligned.y * DRAG_Y_FLY, vAligned.z * DRAG_XZ_FLY);
        }
        return pos;
    }

    private Vec3 simulateBoostedElytraFuturePos(Player player, int ticks) {
        Vec3 pos = player.position();
        Vec3 vel = player.getDeltaMovement();

        final double BOOST_DRAG = 0.991;

        for (int i = 0; i < ticks; i++) {
            pos = pos.add(vel);

            double vy = vel.y;
            if (vy < TERMINAL_VELOCITY) vy = TERMINAL_VELOCITY;

            vel = new Vec3(vel.x * BOOST_DRAG, vy, vel.z * BOOST_DRAG);
        }
        return pos;
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue() || target == null) return;
        draw(event);
    }

    private void draw(Render3DEvent event) {
        long now = System.currentTimeMillis();
        double fadeMs = fadeTime.getValue() * 1000.0;

        boolean drawDebugPrediction = debugPrediction.getValue() && !debugElytraPath.isEmpty();
        if (renderLastPlacedBlock.isEmpty() && renderLastRemovedBlock.isEmpty() && !drawDebugPrediction) return;

        RenderShape shape = shapeMode.getValue();
        float lw = lineWidth.getValue();
        Color fc = fillColor.getValue();
        Color oc = outlineColor.getValue();

        for (Map.Entry<BlockPos, Long> entry : renderLastPlacedBlock.entrySet()) {
            double elapsed = now - entry.getValue();
            if (fadeMs > 0 && elapsed > fadeMs) continue;
            double timeCompletion = fadeMs > 0 ? elapsed / fadeMs : 0.0;

            Color sideColorVal = withAlpha(fc, (int) (fc.getAlpha() * (1.0 - timeCompletion)));
            Color lineColorVal = withAlpha(oc, (int) (oc.getAlpha() * (1.0 - timeCompletion)));
            drawBlock(event, entry.getKey(), sideColorVal, lineColorVal, shape, lw);
        }

        for (Map.Entry<BlockPos, Long> entry : renderLastRemovedBlock.entrySet()) {
            double elapsed = now - entry.getValue();
            if (fadeMs > 0 && elapsed > fadeMs) continue;
            double timeCompletion = fadeMs > 0 ? elapsed / fadeMs : 0.0;

            Color sideColorVal = withAlpha(fc, (int) (fc.getAlpha() * (1.0 - timeCompletion)));
            Color lineColorVal = withAlpha(oc, (int) (oc.getAlpha() * (1.0 - timeCompletion)));
            drawBlock(event, entry.getKey(), sideColorVal, lineColorVal, shape, lw);
        }

        if (drawDebugPrediction) {
            drawDebugPrediction(event, lw);
        }
    }

    private void drawDebugPrediction(Render3DEvent event, float lineWidth) {
        Color pathColor = new Color(0, 213, 255, 170);
        Color boxColor = new Color(255, 0, 128, 170);
        Color pointColor = new Color(0, 213, 255, 68);

        for (int i = 1; i < debugElytraPath.size(); i++) {
            RenderUtil.drawLine(debugElytraPath.get(i - 1).add(0.0, 0.6 * 0.5, 0.0),
                debugElytraPath.get(i).add(0.0, 0.6 * 0.5, 0.0), pathColor, Math.max(1.0f, lineWidth));
        }

        for (Vec3 point : debugElytraPath) {
            AABB marker = AABB.ofSize(point.add(0.0, 0.6 * 0.5, 0.0), 0.12, 0.12, 0.12);
            RenderUtil.drawBoxFilled(event.getMatrix(), marker, pointColor);
            RenderUtil.drawBox(event.getMatrix(), marker, pathColor, Math.max(1.0f, lineWidth));
        }

        if (debugElytraBox != null) {
            RenderUtil.drawBox(event.getMatrix(), debugElytraBox, boxColor, Math.max(1.5f, lineWidth + 0.5f));
        }
    }

    private void drawBlock(Render3DEvent event, BlockPos pos, Color sideColor, Color lineColor, RenderShape shape, float lw) {
        AABB box = new AABB(pos);
        if (shape == RenderShape.BOTH || shape == RenderShape.SIDES) {
            RenderUtil.drawBoxFilled(event.getMatrix(), box, sideColor);
        }
        if (shape == RenderShape.BOTH || shape == RenderShape.LINES) {
            RenderUtil.drawBox(event.getMatrix(), box, lineColor, lw);
        }
    }

    private boolean isIgnoredCrawlingTarget(Player player) {
        return ignoreCrawled.getValue() && player != null && player.isVisuallyCrawling() && !player.isFallFlying();
    }

    private boolean isBadTarget(Player player, double range) {
        if (player == null || mc.player == null || mc.level == null) return true;
        if (player.isDeadOrDying() || player.getHealth() <= 0) return true;
        if (mc.player.distanceToSqr(player) > range * range) return true;
        TargetsModule targets = Swedenhack.moduleManager.getModuleByClass(TargetsModule.class);
        if (targets != null && !targets.isValidPlayerTarget(player)) return true;
        return !player.isAlive();
    }

    private Player findTrapTarget() {
        if (mc.player == null || mc.level == null) return null;

        TargetsModule targets = Swedenhack.moduleManager.getModuleByClass(TargetsModule.class);
        List<Player> candidates = new ArrayList<>();
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (player.isDeadOrDying() || player.getHealth() <= 0) continue;
            if (mc.player.distanceToSqr(player) > 14.0 * 14.0) continue;
            if (targets != null && !targets.isValidPlayerTarget(player)) continue;
            if (isIgnoredCrawlingTarget(player)) continue;
            candidates.add(player);
        }

        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingDouble(p -> mc.player.distanceToSqr(p)));

        return candidates.get(0);
    }

    private Player findLogoutTarget() {
        if (mc.player == null || mc.level == null) return null;
        LogoutSpotsModule module = Swedenhack.moduleManager.getModuleByClass(LogoutSpotsModule.class);
        if (module == null || !module.isEnabled()) return null;

        Vec3 playerPos = mc.player.position();
        double bestDistSq = 14.0 * 14.0;
        LogoutSpotsModule.LogoutSpot bestEntry = null;

        for (LogoutSpotsModule.LogoutSpot entry : module.getLoggedPlayers().values()) {
            if (!mc.level.dimension().equals(entry.dimension)) continue;
            if (entry.entity == null || !entry.entity.isAlive()) continue;
            double distSq = playerPos.distanceToSqr(entry.pos);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestEntry = entry;
            }
        }

        return bestEntry != null ? bestEntry.entity : null;
    }

    private void pruneOwned() {
        if (ownedQueued.isEmpty()) return;
        Swedenhack.placementManager.removeQueuedFor(p -> ownedQueued.contains(p) && !wantedPoses.contains(p));
        ownedQueued.removeIf(p -> !wantedPoses.contains(p));
    }

    private void pruneAll() {
        if (!ownedQueued.isEmpty()) {
            Swedenhack.placementManager.removeQueuedFor(ownedQueued::contains);
            ownedQueued.clear();
        }
        wantedPoses.clear();
    }

    @Override
    public String getDisplayInfo() {
        if (nullCheck() || target == null) return null;
        return target.getName().getString();
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Mth.clamp(a, 0, 255));
    }

    private static Vec3 clampClosestPoint(Vec3 eyePos, AABB box) {
        double x = Mth.clamp(eyePos.x, box.minX, box.maxX);
        double y = Mth.clamp(eyePos.y, box.minY, box.maxY);
        double z = Mth.clamp(eyePos.z, box.minZ, box.maxZ);
        return new Vec3(x, y, z);
    }

    private static int getAxisComponent(BlockPos pos, Direction.Axis axis) {
        return switch (axis) {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }

    private static double getAxisComponent(Vec3 vec, Direction.Axis axis) {
        return switch (axis) {
            case X -> vec.x;
            case Y -> vec.y;
            case Z -> vec.z;
        };
    }

    private static BlockPos offsetAlongAxis(BlockPos pos, Direction.Axis axis, int amount) {
        return switch (axis) {
            case X -> pos.offset(amount, 0, 0);
            case Y -> pos.offset(0, amount, 0);
            case Z -> pos.offset(0, 0, amount);
        };
    }
}
