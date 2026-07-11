package dev.leonetic.features.modules.funny;

import com.mojang.authlib.GameProfile;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public class FakePlayerModule extends Module {
    public enum MovementMode {
        Static,
        Follow,
        Wander,
        Crazy
    }

    private final Setting<MovementMode> movementMode = mode("Movement Mode", MovementMode.Static);
    private final Setting<Float> speed = num("Speed", 0.2f, 0.05f, 1.0f).setVisibility(v -> movementMode.getValue() != MovementMode.Static);
    private final Setting<Boolean> jump = bool("Jump", true).setVisibility(v -> movementMode.getValue() != MovementMode.Static);
    private final Setting<Float> distance = num("Distance", 3.0f, 1.0f, 10.0f);
    private final Setting<String> name = str("Name", "FakePlayer");
    private final Setting<Boolean> copyInventory = bool("Copy Inventory", true);
    private final Setting<Boolean> copyHealth = bool("Copy Health", true);

    private RemotePlayer fakePlayer;
    private UUID fakePlayerId;
    private double wanderAngle = 0.0;

    public FakePlayerModule() {
        super("FakePlayer", "Spawns a fake player to test combat modules or check configurations.", Category.FUNNY);
        this.fakePlayerId = UUID.randomUUID();
    }

    @Override
    public void onEnable() {
        if (nullCheck()) { disable(); return; }
        wanderAngle = 0.0;
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
        if (fakePlayer == null || mc.player == null || mc.level == null) return;

        if (copyHealth.getValue()) {
            fakePlayer.setHealth(mc.player.getHealth());
        }

        MovementMode mode = movementMode.getValue();
        if (mode == MovementMode.Static) {
            return;
        }

        double dx = 0.0;
        double dz = 0.0;
        double dy = fakePlayer.getDeltaMovement().y;

        boolean onGround = fakePlayer.onGround();
        if (!onGround) {
            dy -= 0.08;
            if (dy < -3.92) dy = -3.92;
        } else {
            dy = 0.0;
        }

        switch (mode) {
            case Follow -> {
                Vec3 toPlayer = mc.player.position().subtract(fakePlayer.position());
                double dist = toPlayer.horizontalDistance();
                if (dist > 1.5) {
                    Vec3 dir = toPlayer.normalize();
                    dx = dir.x * speed.getValue();
                    dz = dir.z * speed.getValue();
                    float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
                    fakePlayer.setYRot(yaw);
                    fakePlayer.yHeadRot = yaw;
                    fakePlayer.yBodyRot = yaw;
                }
            }
            case Wander -> {
                wanderAngle += (Math.random() - 0.5) * 0.5;
                dx = Math.sin(wanderAngle) * speed.getValue();
                dz = Math.cos(wanderAngle) * speed.getValue();
                float yaw = (float) Math.toDegrees(wanderAngle);
                fakePlayer.setYRot(yaw);
                fakePlayer.yHeadRot = yaw;
                fakePlayer.yBodyRot = yaw;
            }
            case Crazy -> {
                double time = System.currentTimeMillis() / 100.0;
                double angle = time * 0.8 + Math.sin(time * 0.3) * 2.0;
                dx = Math.sin(angle) * (speed.getValue() * 1.5);
                dz = Math.cos(angle) * (speed.getValue() * 1.5);

                float yaw = (float) (time * 25.0 % 360.0);
                float pitch = (float) (Math.sin(time * 0.5) * 45.0);
                fakePlayer.setYRot(yaw);
                fakePlayer.setXRot(pitch);
                fakePlayer.yHeadRot = yaw;
                fakePlayer.yBodyRot = yaw;

                if (onGround && Math.random() < 0.15) {
                    dy = 0.42;
                }
            }
        }

        Vec3 desiredMove = new Vec3(dx, dy, dz);
        Vec3 actualMove = checkCollision(desiredMove);

        if (jump.getValue() && onGround && (Math.abs(actualMove.x - dx) > 0.01 || Math.abs(actualMove.z - dz) > 0.01)) {
            dy = 0.42;
            desiredMove = new Vec3(dx, dy, dz);
            actualMove = checkCollision(desiredMove);
        }

        fakePlayer.setDeltaMovement(actualMove);
        fakePlayer.setPos(
            fakePlayer.getX() + actualMove.x,
            fakePlayer.getY() + actualMove.y,
            fakePlayer.getZ() + actualMove.z
        );

        AABB movedBox = fakePlayer.getBoundingBox();
        boolean nextOnGround = !mc.level.noCollision(fakePlayer, movedBox.move(0.0, -0.05, 0.0));
        fakePlayer.setOnGround(nextOnGround);
    }

    private Vec3 checkCollision(Vec3 movement) {
        if (mc.level == null) return movement;

        AABB box = fakePlayer.getBoundingBox();

        double dy = movement.y;
        if (Math.abs(dy) > 0.001) {
            dy = clipAxis(box, dy, Direction.Axis.Y);
            box = box.move(0.0, dy, 0.0);
        }

        double dx = movement.x;
        if (Math.abs(dx) > 0.001) {
            dx = clipAxis(box, dx, Direction.Axis.X);
            box = box.move(dx, 0.0, 0.0);
        }

        double dz = movement.z;
        if (Math.abs(dz) > 0.001) {
            dz = clipAxis(box, dz, Direction.Axis.Z);
        }

        return new Vec3(dx, dy, dz);
    }

    private double clipAxis(AABB box, double desired, Direction.Axis axis) {
        if (Math.abs(desired) <= 1.0E-5) return 0.0;

        AABB moved = moveBox(box, axis, desired);
        if (mc.level.noCollision(fakePlayer, moved.deflate(1.0E-4, 1.0E-4, 1.0E-4))) {
            return desired;
        }

        double best = 0.0;
        double low = 0.0;
        double high = 1.0;
        for (int i = 0; i < 8; i++) {
            double mid = (low + high) * 0.5;
            double candidate = desired * mid;
            if (mc.level.noCollision(fakePlayer, moveBox(box, axis, candidate).deflate(1.0E-4, 1.0E-4, 1.0E-4))) {
                best = candidate;
                low = mid;
            } else {
                high = mid;
            }
        }
        return Math.abs(best) <= 1.0E-5 ? 0.0 : best;
    }

    private AABB moveBox(AABB box, Direction.Axis axis, double amount) {
        return switch (axis) {
            case X -> box.move(amount, 0.0, 0.0);
            case Y -> box.move(0.0, amount, 0.0);
            case Z -> box.move(0.0, 0.0, amount);
        };
    }
}