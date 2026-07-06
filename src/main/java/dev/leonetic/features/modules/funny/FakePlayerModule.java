package dev.leonetic.features.modules.funny;

import com.mojang.authlib.GameProfile;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

public class FakePlayerModule extends Module {
    private final Setting<Float>   distance      = num("Distance", 3.0f, 1.0f, 10.0f);
    private final Setting<String>  name          = str("Name", "FakePlayer");
    private final Setting<Boolean> copyInventory = bool("Copy Inventory", true);
    private final Setting<Boolean> copyHealth    = bool("Copy Health", true);

    private RemotePlayer fakePlayer;
    private UUID         fakePlayerId;

    public FakePlayerModule() {
        super("FakePlayer", "Spawns a static fake player to test combat modules on.", Category.FUNNY);
        this.fakePlayerId = UUID.randomUUID();
    }

    @Override
    public void onEnable() {
        if (nullCheck()) { disable(); return; }
        spawnFakePlayer();
    }

    @Override
    public void onDisable() {
        removeFakePlayer();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) { disable(); return; }
        if (fakePlayer == null || !fakePlayer.isAlive()) { spawnFakePlayer(); return; }
        updateFakePlayer();
    }

    private void spawnFakePlayer() {
        if (mc.level == null || mc.player == null) return;

        GameProfile profile = new GameProfile(fakePlayerId, name.getValue());
        fakePlayer = new RemotePlayer(mc.level, profile) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative()  { return false; }
        };

        // Spawn next to the player at enable-time; stays here forever
        fakePlayer.setPos(
            mc.player.getX() + distance.getValue(),
            mc.player.getY(),
            mc.player.getZ()
        );
        fakePlayer.setYRot(mc.player.getYRot());
        fakePlayer.setXRot(mc.player.getXRot());

        if (copyInventory.getValue()) {
            fakePlayer.getInventory().replaceWith(mc.player.getInventory());
        }
        if (copyHealth.getValue()) {
            fakePlayer.setHealth(mc.player.getHealth());
            fakePlayer.getFoodData().setFoodLevel(mc.player.getFoodData().getFoodLevel());
        }

        mc.level.addEntity(fakePlayer);
    }

    private void removeFakePlayer() {
        if (fakePlayer != null && mc.level != null) {
            mc.level.removeEntity(fakePlayer.getId(), Entity.RemovalReason.DISCARDED);
            fakePlayer = null;
        }
    }

    private void updateFakePlayer() {
        if (fakePlayer == null || mc.player == null) return;
        // Static — position never updated, only sync health
        if (copyHealth.getValue()) {
            fakePlayer.setHealth(mc.player.getHealth());
        }
    }
}