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
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import static dev.leonetic.util.inventory.InventoryUtil.FULL_SCOPE;

public class AutoMaceModule extends Module {

    // canSmashAttack() is server-side only - always false on client.
    // We replicate the check: fallDistance > SMASH_ATTACK_FALL_THRESHOLD (1.5f)
    // and the entity must not be flying elytra.
    private static final float  SMASH_FALL_THRESHOLD  = 1.5f;
    private static final double ATTACK_RANGE          = 3.5;
    private static final String ID                    = "AutoMace";
    private static final int    ROTATION_PRIORITY     = 85;
    private static final int    SWAP_PRIORITY         = 70;
    private static final int    ROCKET_SWAP_PRIORITY  = 15;
    private static final int    CHEST_MENU_SLOT       = 6;
    private static final int    OFFHAND_MENU_SLOT     = 45;
    private static final double TAKEOFF_VEL           = 0.42;
    private static final float  LAUNCH_PITCH          = -65f;

    private final Setting<Double>  range     = num("Range",     8.0, 1.0, 20.0);
    private final Setting<Double>  minHeight = num("MinHeight", 8.0, 2.0, 30.0);
    private final Setting<Boolean> render    = bool("Render", true);

    private enum Phase { IDLE, EQUIP, TAKEOFF, LAUNCH, STALL, DIVE, ATTACK, RESTORE }

    private Phase   phase            = Phase.IDLE;
    private Entity  currentTarget    = null;
    private boolean weEquipped       = false;
    private int     stowedChestSlot  = -1;
    private int     ticksSinceRocket = Integer.MAX_VALUE / 2;
    private int     refireTicks      = 0;
    private int     stallTicks       = 0;

    public AutoMaceModule() {
        super(ID, "Rockets up at angle, stalls, dives, smashes with mace.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        phase = Phase.IDLE; currentTarget = null; weEquipped = false;
        stowedChestSlot = -1; ticksSinceRocket = Integer.MAX_VALUE / 2; stallTicks = 0;
    }

    @Override
    public void onDisable() {
        Swedenhack.rotationManager.cancel(ID);
        restoreChest();
        currentTarget = null;
        phase = Phase.IDLE;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        switch (phase) {
            case IDLE    -> tickIdle();
            case EQUIP   -> tickEquip();
            case TAKEOFF -> tickTakeoff();
            case LAUNCH  -> tickLaunch();
            case STALL   -> tickStall();
            case DIVE    -> tickDive();
            case ATTACK  -> tickAttack();
            case RESTORE -> tickRestore();
        }
    }

    private void tickIdle() {
        currentTarget = findTarget();
        if (currentTarget == null) return;
        phase = Phase.EQUIP;
    }

    private void tickEquip() {
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) { phase = Phase.TAKEOFF; return; }
        if (mc.gameMode == null || mc.player.containerMenu != mc.player.inventoryMenu) return;
        Result elytra = InventoryUtil.find(Items.ELYTRA, FULL_SCOPE);
        if (!elytra.found()) { phase = Phase.IDLE; return; }
        int slot = containerSlotOf(elytra);
        swapChestWith(slot);
        stowedChestSlot = slot;
        weEquipped = true;
        phase = Phase.TAKEOFF;
    }

    private void tickTakeoff() {
        if (mc.player.isFallFlying()) { ticksSinceRocket = Integer.MAX_VALUE / 2; phase = Phase.LAUNCH; return; }
        if (mc.getConnection() == null) return;
        if (mc.player.onGround()) {
            Vec3 vel = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(vel.x, TAKEOFF_VEL, vel.z);
            mc.player.setSprinting(true);
            return;
        }
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        mc.player.startFallFlying();
    }

    private void tickLaunch() {
        if (!mc.player.isFallFlying()) { phase = Phase.TAKEOFF; return; }
        currentTarget = findTarget();
        if (currentTarget == null) { phase = Phase.RESTORE; return; }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 tgt = currentTarget.getBoundingBox().getCenter();
        float launchYaw = MathUtil.calcAngle(eye, tgt)[0];
        Swedenhack.rotationManager.submit(new RotationRequest(
            ID, ROTATION_PRIORITY, launchYaw, LAUNCH_PITCH,
            RotationRequest.Mode.MOTION, true, true));
        ticksSinceRocket++;
        if (ticksSinceRocket >= refireTicks) fireRocket();
        if (mc.player.getY() - tgt.y >= minHeight.getValue()) {
            Swedenhack.rotationManager.cancel(ID);
            stallTicks = 0;
            phase = Phase.STALL;
        }
    }

    private void tickStall() {
        stallTicks++;
        Vec3 vel = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(0.0, vel.y, 0.0);
        if (mc.player.isFallFlying()) {
            mc.player.closeContainer();
            return;
        }
        if (stallTicks >= 3) {
            mc.player.setDeltaMovement(0.0, -0.1, 0.0);
            phase = Phase.DIVE;
        }
    }

    // We check fallDistance ourselves because canSmashAttack() only works server-side.
    // Vanilla threshold is SMASH_ATTACK_FALL_THRESHOLD = 1.5f.
    private void tickDive() {
        currentTarget = findTarget();
        if (currentTarget == null) { phase = Phase.RESTORE; return; }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 tgt = currentTarget.getBoundingBox().getCenter();
        float[] ang = MathUtil.calcAngle(eye, tgt);
        Swedenhack.rotationManager.submit(new RotationRequest(
            ID, ROTATION_PRIORITY, ang[0], ang[1], RotationRequest.Mode.SILENT));
        // Attack conditions (mirrors server canSmashAttack logic):
        // - not elytra flying
        // - not on ground
        // - fallDistance > threshold
        // - within attack range of target
        boolean notFlying   = !mc.player.isFallFlying();
        boolean notOnGround = !mc.player.onGround();
        boolean goodFall    = mc.player.fallDistance > SMASH_FALL_THRESHOLD;
        boolean inRange     = eye.distanceTo(tgt) <= ATTACK_RANGE;
        if (notFlying && notOnGround && goodFall && inRange) {
            phase = Phase.ATTACK;
            return;
        }
        if (mc.player.onGround()) {
            Swedenhack.rotationManager.cancel(ID);
            phase = Phase.RESTORE;
        }
    }

    private void tickAttack() {
        currentTarget = findTarget();
        if (currentTarget == null) {
            Swedenhack.rotationManager.cancel(ID); phase = Phase.RESTORE; return;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 tgt = currentTarget.getBoundingBox().getCenter();
        float[] ang = MathUtil.calcAngle(eye, tgt);
        Swedenhack.rotationManager.submit(new RotationRequest(
            ID, ROTATION_PRIORITY, ang[0], ang[1], RotationRequest.Mode.SILENT));
        int maceSlot = findMaceSlot();
        if (maceSlot == -1) { Swedenhack.rotationManager.cancel(ID); phase = Phase.RESTORE; return; }
        int origSlot = Swedenhack.swapManager.serverSlot();
        boolean needSwap = maceSlot != origSlot;
        if (needSwap && mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) return;
        SwapManager.SwapHandle handle = null;
        if (needSwap) {
            handle = Swedenhack.swapManager.acquire(ID, SWAP_PRIORITY);
            if (handle == null) return;
        }
        try {
            if (needSwap) mc.getConnection().send(new ServerboundSetCarriedItemPacket(maceSlot));
            mc.gameMode.attack(mc.player, currentTarget);
            mc.player.swing(InteractionHand.MAIN_HAND);
            if (needSwap) mc.getConnection().send(new ServerboundSetCarriedItemPacket(origSlot));
        } finally {
            if (handle != null) Swedenhack.swapManager.release(handle);
        }
        Swedenhack.rotationManager.cancel(ID);
        phase = Phase.RESTORE;
    }

    private void tickRestore() { restoreChest(); phase = Phase.IDLE; }

    private void restoreChest() {
        if (!weEquipped) return;
        if (mc.gameMode == null || mc.player.containerMenu != mc.player.inventoryMenu) return;
        if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) return;
        if (stowedChestSlot != -1) {
            swapChestWith(stowedChestSlot);
        } else {
            Result plate = InventoryUtil.find(AutoMaceModule::isChestplate, FULL_SCOPE);
            if (plate.found()) swapChestWith(containerSlotOf(plate));
        }
        weEquipped = false; stowedChestSlot = -1;
    }

    private void fireRocket() {
        if (mc.gameMode == null || mc.player.containerMenu != mc.player.inventoryMenu) return;
        Result rocket = InventoryUtil.find(Items.FIREWORK_ROCKET, FULL_SCOPE);
        if (!rocket.found()) return;
        ticksSinceRocket = 0;
        refireTicks = InventoryUtil.fireworkRefireTicks(rocket.stack());
        if (rocket.type() == ResultType.HOTBAR && rocket.slot() == InventoryUtil.selected()) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            return;
        }
        SwapManager.SwapHandle handle = Swedenhack.swapManager.acquire(ID, ROCKET_SWAP_PRIORITY);
        if (handle == null) return;
        try {
            int cs = switch (rocket.type()) {
                case OFFHAND -> OFFHAND_MENU_SLOT;
                case HOTBAR  -> rocket.slot() + 36;
                default      -> rocket.slot();
            };
            InventoryUtil.click(cs, InventoryUtil.selected(), ClickType.SWAP);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            InventoryUtil.click(cs, InventoryUtil.selected(), ClickType.SWAP);
        } finally { Swedenhack.swapManager.release(handle); }
    }

    private void swapChestWith(int cs) {
        InventoryUtil.click(cs, 0, ClickType.PICKUP);
        InventoryUtil.click(CHEST_MENU_SLOT, 0, ClickType.PICKUP);
        InventoryUtil.click(cs, 0, ClickType.PICKUP);
    }

    private int containerSlotOf(Result r) {
        if (r.type() == ResultType.OFFHAND) return OFFHAND_MENU_SLOT;
        int s = r.slot(); return s < 9 ? s + 36 : s;
    }

    private static boolean isChestplate(ItemStack stack) {
        if (stack.is(Items.ELYTRA)) return false;
        Equippable eq = stack.get(DataComponents.EQUIPPABLE);
        return eq != null && eq.slot() == EquipmentSlot.CHEST;
    }

    private Entity findTarget() {
        TargetsModule targets = Swedenhack.moduleManager.getModuleByClass(TargetsModule.class);
        if (targets == null) return null;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Entity best = null; double bestDist = Double.MAX_VALUE; double r = range.getValue();
        for (Entity e : mc.level.getEntities(null, mc.player.getBoundingBox().inflate(r + 2))) {
            if (!targets.isValidTarget(e)) continue;
            double dist = eye.distanceTo(e.getBoundingBox().getCenter());
            if (dist > r || dist >= bestDist) continue;
            bestDist = dist; best = e;
        }
        return best;
    }

    private int findMaceSlot() {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getItem(i).getItem() instanceof MaceItem) return i;
        return -1;
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

    @Override
    public String getDisplayInfo() {
        return switch (phase) {
            case IDLE    -> null;
            case EQUIP   -> "Equipping";
            case TAKEOFF -> "Takeoff";
            case LAUNCH  -> "Ascending";
            case STALL   -> "Stalling";
            case DIVE    -> "Diving";
            case ATTACK  -> "Smashing";
            case RESTORE -> "Restoring";
        };
    }
}
