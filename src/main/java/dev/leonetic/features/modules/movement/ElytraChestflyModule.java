package dev.leonetic.features.modules.movement;

import dev.leonetic.Swedenhack;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.inventory.ResultType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

import static dev.leonetic.util.inventory.InventoryUtil.FULL_SCOPE;

public class ElytraChestflyModule extends Module {

    private static final String ID = "ElytraChestfly";
    private static final int CHEST_SLOT = 6;
    private static final int OFFHAND_SLOT = 45;

    private final Setting<Boolean> autoRocket = bool("AutoRocket", true);
    private final Setting<Integer> rocketDelay = num("RocketDelay", 2, 0, 10);
    private final Setting<Boolean> swing = bool("Swing", true);
    private final Setting<Boolean> offhand = bool("Offhand", false);

    private int tickCounter = 0;
    private int chestplateSlot = -1;
    private int elytraSlot = -1;
    private int ticksSinceRocket = 0;

    public ElytraChestflyModule() {
        super("ElytraChestfly", "Chestfly - bypass detection by swapping elytra/chestplate rapidly", Category.MOVEMENT);
    }

    @Override
    public void onEnable() {
        tickCounter = 0;
        chestplateSlot = -1;
        elytraSlot = -1;
        ticksSinceRocket = 0;
        
        // Find slots
        Result elytraResult = InventoryUtil.find(Items.ELYTRA, FULL_SCOPE);
        Result chestResult = InventoryUtil.find(ElytraChestflyModule::isChestplate, FULL_SCOPE);
        
        if (elytraResult.found()) {
            elytraSlot = toContainerSlot(elytraResult);
        }
        if (chestResult.found()) {
            chestplateSlot = toContainerSlot(chestResult);
        }
    }

    @Override
    public void onDisable() {
        // Restore chestplate if we have it
        if (chestplateSlot != -1 && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            swapChestSlot(chestplateSlot);
        }
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;
        if (!canClickInventory()) return;

        // Ensure we have required items
        if (elytraSlot == -1) {
            Result elytraResult = InventoryUtil.find(Items.ELYTRA, FULL_SCOPE);
            if (elytraResult.found()) {
                elytraSlot = toContainerSlot(elytraResult);
            } else {
                disable();
                return;
            }
        }

        if (chestplateSlot == -1) {
            Result chestResult = InventoryUtil.find(ElytraChestflyModule::isChestplate, FULL_SCOPE);
            if (chestResult.found()) {
                chestplateSlot = toContainerSlot(chestResult);
            }
        }

        // Start flying if not already
        if (!mc.player.isFallFlying()) {
            if (mc.player.onGround()) {
                mc.player.jumpFromGround();
            } else {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                        mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                mc.player.startFallFlying();
            }
        }

        // Chestfly cycle
        tickCounter++;
        
        // Even ticks: equip elytra and glide
        if (tickCounter % 2 == 0) {
            if (elytraSlot != -1) {
                swapChestSlot(elytraSlot);
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                        mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
        } 
        // Odd ticks: equip chestplate
        else {
            if (chestplateSlot != -1) {
                swapChestSlot(chestplateSlot);
            }
        }

        // Auto rocket
        if (autoRocket.getValue()) {
            ticksSinceRocket++;
            if (ticksSinceRocket >= rocketDelay.getValue()) {
                fireRocket();
                ticksSinceRocket = 0;
            }
        }
    }

    private void fireRocket() {
        Result rocket = InventoryUtil.find(Items.FIREWORK_ROCKET, FULL_SCOPE);
        if (!rocket.found()) return;

        if (offhand.getValue()) {
            // Offhand mode
            if (rocket.type() == ResultType.OFFHAND) {
                mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
                if (swing.getValue()) {
                    mc.player.swing(InteractionHand.OFF_HAND);
                }
            } else {
                int rocketSlot = toContainerSlot(rocket);
                InventoryUtil.click(rocketSlot, OFFHAND_SLOT, ClickType.SWAP);
                mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
                if (swing.getValue()) {
                    mc.player.swing(InteractionHand.OFF_HAND);
                }
                InventoryUtil.click(rocketSlot, OFFHAND_SLOT, ClickType.SWAP);
            }
        } else {
            // Main hand mode
            int selected = InventoryUtil.selected();
            if (rocket.type() == ResultType.HOTBAR && rocket.slot() == selected) {
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                if (swing.getValue()) {
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
            } else {
                int rocketSlot = toContainerSlot(rocket);
                InventoryUtil.click(rocketSlot, selected, ClickType.SWAP);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                if (swing.getValue()) {
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
                InventoryUtil.click(rocketSlot, selected, ClickType.SWAP);
            }
        }
    }

    private void swapChestSlot(int itemSlot) {
        InventoryUtil.click(itemSlot, 0, ClickType.PICKUP);
        InventoryUtil.click(CHEST_SLOT, 0, ClickType.PICKUP);
        InventoryUtil.click(itemSlot, 0, ClickType.PICKUP);
    }

    private int toContainerSlot(Result result) {
        if (result.type() == ResultType.OFFHAND) return OFFHAND_SLOT;
        int slot = result.slot();
        return slot < 9 ? slot + 36 : slot;
    }

    private static boolean isChestplate(ItemStack stack) {
        if (stack.is(Items.ELYTRA)) return false;
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.CHEST;
    }

    private boolean canClickInventory() {
        return mc.gameMode != null && mc.player.containerMenu == mc.player.inventoryMenu;
    }

    @Override
    public String getDisplayInfo() {
        return mc.player.isFallFlying() ? "Active" : null;
    }
}
