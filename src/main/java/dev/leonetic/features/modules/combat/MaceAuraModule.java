package dev.leonetic.features.modules.combat;

import dev.leonetic.Swedenhack;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.TargetsModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.manager.SwapManager;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.inventory.ResultType;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static dev.leonetic.util.inventory.InventoryUtil.FULL_SCOPE;

/**
 * Automatically attacks targets with a mace using vanilla delays.
 */
public class MaceAuraModule extends Module {

    private static final String ROTATION_ID = "mace-aura";
    private static final double PREDICTION_MS = 120.0;
    private static final double ELYTRA_PREDICTION_SCALE = 1.4;
    private static final double FLYING_RANGE_BONUS = 0.3;
    private static final double AIM_Y_OFFSET = -0.2;

    // ─── Settings ────────────────────────────────────────────────
    private final Setting<Double> range = num("Range", 3.0, 1.0, 6.0);
    private final Setting<Boolean> rotate = bool("Rotate", true);
    private final Setting<Boolean> fastSwap = bool("Fast Swap", true);
    private final Setting<Integer> hitCooldownMs = num("Hit Cooldown", 150, 0, 1000);
    private final Setting<Boolean> chestSwap = bool("Chest Swap", false);
    private final Setting<Double> swapRange = num("Swap Range", 6.0, 1.0, 12.0);
    private final Setting<Boolean> render = bool("Render", true);

    // ─── State ───────────────────────────────────────────────────
    private long lastAttackTimeMs = 0L;
    private Entity currentTarget = null;

    public MaceAuraModule() {
        super("MaceAura", "Automatically attacks targets with a mace using vanilla delays.", Category.COMBAT);
    }

    @Override
    public void onDisable() {
        Swedenhack.rotationManager.cancel(ROTATION_ID);
        currentTarget = null;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.player.isDeadOrDying() || mc.player.isSpectator()) return;

        // Get mace or netherite axe slot for silent swap
        Result mace = InventoryUtil.find(Items.MACE, FULL_SCOPE);
        Result netheriteAxe = InventoryUtil.find(Items.NETHERITE_AXE, FULL_SCOPE);
        Result mainWeapon = mace.found() ? mace : netheriteAxe;

        if (!mainWeapon.found()) {
            Swedenhack.rotationManager.cancel(ROTATION_ID);
            currentTarget = null;
            return;
        }

        // Find targets
        currentTarget = findTarget();
        if (currentTarget == null) {
            Swedenhack.rotationManager.cancel(ROTATION_ID);
            return;
        }

        Vec3 eyes = mc.player.getEyePosition(1.0f);
        double acqRange = range.getValue();
        if (isEntityFlying(currentTarget)) acqRange += FLYING_RANGE_BONUS;

        double dist = getClosestPointOnBox(currentTarget.getBoundingBox(), eyes).distanceTo(eyes);
        if (dist > acqRange) {
            Swedenhack.rotationManager.cancel(ROTATION_ID);
            return;
        }

        // ChestSwap: if elytra equipped and target within swap range, swap to chestplate
        if (chestSwap.getValue() && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            double swapDist = getClosestPointOnBox(currentTarget.getBoundingBox(), eyes).distanceTo(eyes);
            if (swapDist <= swapRange.getValue()) {
                silentSwapEquipChestplate();
            }
        }

        // Rotation
        if (rotate.getValue()) {
            Vec3 point = predictedAimPoint(currentTarget, eyes);
            float[] angles = MathUtil.calcAngle(eyes, point);
            Swedenhack.rotationManager.submit(new RotationRequest(
                ROTATION_ID, 85, angles[0], angles[1], RotationRequest.Mode.SILENT
            ));
        }

        // Delay check
        int delayCheckSlot = fastSwap.getValue()
            ? mc.player.getInventory().getSelectedSlot()
            : getHotbarSlot(mainWeapon);

        if (!delayReady(delayCheckSlot)) return;

        // Swap & attack
        int weaponSlot = getHotbarSlot(mainWeapon);
        if (weaponSlot == -1) return;

        boolean isHolding = weaponSlot == mc.player.getInventory().getSelectedSlot();
        int originalSlot = Swedenhack.swapManager.serverSlot();
        boolean needSwap = weaponSlot != originalSlot;

        if (needSwap && mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) return;

        SwapManager.SwapHandle handle = null;
        if (needSwap) {
            handle = Swedenhack.swapManager.acquire(ROTATION_ID, 70);
            if (handle == null) return;
        }

        try {
            if (needSwap) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(weaponSlot));
            }
            attack(currentTarget);
            if (needSwap) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(originalSlot));
            }
        } finally {
            if (handle != null) Swedenhack.swapManager.release(handle);
        }

        Swedenhack.rotationManager.cancel(ROTATION_ID);
    }

    private void attack(Entity target) {
        if (mc.player == null || mc.getConnection() == null) return;

        mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(target, mc.player.isShiftKeyDown()));

        // Re-sync sprint after attack (server resets it on knockback)
        if (mc.player.isSprinting()) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        }

        mc.player.swing(InteractionHand.MAIN_HAND);
        lastAttackTimeMs = System.currentTimeMillis();
    }

    private boolean delayReady(int slot) {
        if (mc.player == null) return false;

        if (!packetCooldownReady()) return false;

        ItemStack stack = mc.player.getInventory().getItem(slot);
        double attackSpeedBase = mc.player.getAttributeBaseValue(Attributes.ATTACK_SPEED);
        double bonus = 0;

        if (stack.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
            var modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            for (var entry : modifiers.modifiers()) {
                if (entry.attribute().is(Attributes.ATTACK_SPEED)
                    && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                    bonus += entry.modifier().amount();
                }
            }
        }

        double attackSpeed = attackSpeedBase + bonus;
        double cooldownTicks = 1.0 / attackSpeed * 20.0;

        // TPS sync
        float tps = Swedenhack.tpsCounterService.getLatestTPS();
        if (tps < 19.5f && tps > 1f) {
            cooldownTicks /= tps / 20.0;
        }

        long now = System.currentTimeMillis();
        return (double) (now - lastAttackTimeMs) / 50.0 > cooldownTicks;
    }

    private boolean packetCooldownReady() {
        if (hitCooldownMs.getValue() <= 0) return true;

        long now = System.currentTimeMillis();
        return now - lastAttackTimeMs >= hitCooldownMs.getValue();
    }

    private Entity findTarget() {
        TargetsModule targets = Swedenhack.moduleManager.getModuleByClass(TargetsModule.class);
        if (targets == null) return null;

        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        double r = range.getValue() + FLYING_RANGE_BONUS;

        Entity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity entity : mc.level.getEntities(null, mc.player.getBoundingBox().inflate(r))) {
            if (entity == mc.player) continue;
            if (!entity.isAlive()) continue;
            if (entity.isSpectator()) continue;
            if (!(entity instanceof LivingEntity)) continue;

            if (!targets.isValidTarget(entity)) continue;

            double dist = eyePos.distanceTo(getClosestPointOnBox(entity.getBoundingBox(), eyePos));
            if (dist > r || dist >= bestDist) continue;

            bestDist = dist;
            best = entity;
        }

        return best;
    }

    private static Vec3 getClosestPointOnBox(AABB box, Vec3 point) {
        double x = Math.max(box.minX, Math.min(point.x, box.maxX));
        double y = Math.max(box.minY, Math.min(point.y, box.maxY));
        double z = Math.max(box.minZ, Math.min(point.z, box.maxZ));
        return new Vec3(x, y, z);
    }

    private boolean isEntityFlying(Entity e) {
        return e.getDeltaMovement().y < -0.1 || e.getDeltaMovement().y > 0.1 || e.fallDistance > 1.5;
    }

    private Vec3 predictedAimPoint(Entity target, Vec3 eyes) {
        Vec3 base = getClosestPointOnBox(target.getBoundingBox(), eyes);
        Vec3 vel = target.getDeltaMovement();

        if (isEntityFlying(target)) vel = vel.scale(ELYTRA_PREDICTION_SCALE);

        Vec3 lead = vel.scale(PREDICTION_MS / 1000.0);
        return base.add(lead.x, lead.y + AIM_Y_OFFSET, lead.z);
    }

    private int getHotbarSlot(Result result) {
        if (result.type() == ResultType.HOTBAR) return result.slot();
        if (result.type() == ResultType.OFFHAND) return -1;
        if (result.slot() < 9) return result.slot();
        return -1;
    }

    private static final int CHEST_MENU_SLOT = 6;

    private void silentSwapEquipChestplate() {
        if (mc.gameMode == null || mc.player.containerMenu != mc.player.inventoryMenu) return;

        Result chestplate = InventoryUtil.find(stack -> {
            if (stack.is(Items.ELYTRA)) return false;
            var equippable = stack.get(DataComponents.EQUIPPABLE);
            return equippable != null && equippable.slot() == EquipmentSlot.CHEST;
        }, FULL_SCOPE);

        if (!chestplate.found()) return;

        int chestSlot = containerSlotOf(chestplate);
        swapChestWith(chestSlot);
    }

    private void swapChestWith(int cs) {
        InventoryUtil.click(cs, 0, net.minecraft.world.inventory.ClickType.PICKUP);
        InventoryUtil.click(CHEST_MENU_SLOT, 0, net.minecraft.world.inventory.ClickType.PICKUP);
        InventoryUtil.click(cs, 0, net.minecraft.world.inventory.ClickType.PICKUP);
    }

    private int containerSlotOf(Result r) {
        if (r.type() == ResultType.OFFHAND) return 45;
        int s = r.slot();
        return s < 9 ? s + 36 : s;
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue() || currentTarget == null || nullCheck()) return;

        float pt = event.getDelta();
        double ix = currentTarget.xOld + (currentTarget.getX() - currentTarget.xOld) * pt;
        double iy = currentTarget.yOld + (currentTarget.getY() - currentTarget.yOld) * pt;
        double iz = currentTarget.zOld + (currentTarget.getZ() - currentTarget.zOld) * pt;
        AABB box = currentTarget.getBoundingBox().move(
            ix - currentTarget.getX(), iy - currentTarget.getY(), iz - currentTarget.getZ());
        RenderUtil.drawBox(event.getMatrix(), box, Swedenhack.colorManager.get("ui"), 1.5f);
    }
}
