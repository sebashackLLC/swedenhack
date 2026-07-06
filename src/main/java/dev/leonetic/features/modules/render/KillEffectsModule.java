package dev.leonetic.features.modules.render;

import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.render.WireframeEntityRenderer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class KillEffectsModule extends Module {

    public final Setting<Boolean> playSound = bool("Thunder Sound", true);
    public final Setting<Double> spread = num("Spread Radius", 1.5, 0.0, 5.0);
    
    public final Setting<Color> chamColor = color("Cham Fill", 0, 255, 255, 100);
    public final Setting<Color> outlineColor = color("Cham Outline", 0, 255, 255, 255);
    public final Setting<Float> lineWidth = num("Line Width", 1.5f, 0.5f, 5.0f);
    public final Setting<Float> floatSpeed = num("Float Speed", 1.5f, 0.1f, 5.0f);
    public final Setting<Integer> duration = num("Duration (ms)", 3000, 500, 10000);

    private final Set<UUID> lastTickAlive = ConcurrentHashMap.newKeySet();
    private final List<HeavenEffect> activeEffects = new ArrayList<>();

    public KillEffectsModule() {
        super("KillEffects", "Spawns 10 lightning bolts and a floating player cham when players die", Category.RENDER);
    }

    @Override
    public void onEnable() {
        lastTickAlive.clear();
        activeEffects.clear();
        if (nullCheck()) return;
        for (Player p : mc.level.players()) {
            if (p != mc.player && p.isAlive()) {
                lastTickAlive.add(p.getUUID());
            }
        }
    }

    @Override
    public void onDisable() {
        lastTickAlive.clear();
        activeEffects.clear();
    }

    @Override
    public void onTick() {
        if (nullCheck()) {
            activeEffects.clear();
            return;
        }
        long now = System.currentTimeMillis();
        long dur = duration.getValue();
        activeEffects.removeIf(effect -> now - effect.startTime > dur);
    }

    @Subscribe
    public void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;

        Set<UUID> currentlyAlive = new HashSet<>();
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            UUID id = player.getUUID();
            if (player.isDeadOrDying()) {
                if (lastTickAlive.contains(id)) {
                    triggerEffect(player);
                }
            } else {
                currentlyAlive.add(id);
            }
        }
        lastTickAlive.clear();
        lastTickAlive.addAll(currentlyAlive);
    }

    @Subscribe
    public void onDisconnect(dev.leonetic.event.impl.network.DisconnectEvent event) {
        lastTickAlive.clear();
        activeEffects.clear();
    }

    private void triggerEffect(Player player) {
        Vec3 pos = player.position();
        double radius = spread.getValue();

        for (int i = 0; i < 10; i++) {
            double offsetX = (mc.level.random.nextDouble() - 0.5) * radius * 2.0;
            double offsetZ = (mc.level.random.nextDouble() - 0.5) * radius * 2.0;
            LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, mc.level);
            bolt.setPos(pos.x + offsetX, pos.y, pos.z + offsetZ);
            mc.level.addEntity(bolt);
        }

        if (playSound.getValue()) {
            mc.level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 10.0f, 0.8f + mc.level.random.nextFloat() * 0.2f, false);
            mc.level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 2.0f, 0.5f + mc.level.random.nextFloat() * 0.2f, false);
        }

        activeEffects.add(new HeavenEffect(player, pos, System.currentTimeMillis()));
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck() || activeEffects.isEmpty()) return;

        float delta = event.getDelta();
        long now = System.currentTimeMillis();
        long dur = duration.getValue();
        float speed = floatSpeed.getValue();

        Color fill = chamColor.getValue();
        Color line = outlineColor.getValue();

        for (HeavenEffect effect : activeEffects) {
            long elapsed = now - effect.startTime;
            if (elapsed > dur) continue;

            double floatOffset = (elapsed / 1000.0) * speed;
            Vec3 renderPos = effect.initialPos.add(0, floatOffset, 0);

            float alpha = 1.0f - ((float) elapsed / dur);
            if (alpha < 0.0f) alpha = 0.0f;
            if (alpha > 1.0f) alpha = 1.0f;

            Color drawFill = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), (int) (fill.getAlpha() * alpha));
            Color drawLine = new Color(line.getRed(), line.getGreen(), line.getBlue(), (int) (line.getAlpha() * alpha));

            effect.capturedGeometry = WireframeEntityRenderer.render(
                    event.getMatrix(),
                    effect.player,
                    renderPos,
                    effect.capturedGeometry,
                    delta,
                    drawFill,
                    drawLine,
                    lineWidth.getValue()
            );
        }
    }

    private static class HeavenEffect {
        final Player player;
        final Vec3 initialPos;
        final long startTime;
        List<float[][]> capturedGeometry = null;

        HeavenEffect(Player player, Vec3 initialPos, long startTime) {
            this.player = player;
            this.initialPos = initialPos;
            this.startTime = startTime;
        }
    }
}
