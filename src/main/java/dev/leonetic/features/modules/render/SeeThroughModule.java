package dev.leonetic.features.modules.render;

import dev.leonetic.Swedenhack;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.render.SeeThroughRender;
import dev.leonetic.util.render.WireframeEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;

import java.awt.Color;

public class SeeThroughModule extends Module {

    public enum ChamsMode {
        Texture,
        Fill,
        Outline,
        Both
    }

    public enum FriendMode {
        Default,
        Custom,
        Sync
    }

    public enum CrystalMode {
        Fill,
        Outline,
        Both
    }

    // General Settings Page
    public Setting<ChamsMode> chamsMode    = mode("Mode", ChamsMode.Texture).setPage("General");
    public Setting<Boolean>   syncColor    = bool("Sync Color", false).setPage("General");
    public Setting<Color>     fillColor    = color("Fill Color", 0, 255, 255, 100).setPage("General").setVisibility(v -> !syncColor.getValue() && (chamsMode.getValue() == ChamsMode.Fill || chamsMode.getValue() == ChamsMode.Both));
    public Setting<Color>     outlineColor = color("Outline Color", 0, 255, 255, 255).setPage("General").setVisibility(v -> !syncColor.getValue() && (chamsMode.getValue() == ChamsMode.Outline || chamsMode.getValue() == ChamsMode.Both));
    public Setting<Float>     lineWidth    = num("Line Width", 1.5f, 0.5f, 5.0f).setPage("General").setVisibility(v -> chamsMode.getValue() == ChamsMode.Outline || chamsMode.getValue() == ChamsMode.Both);
    public Setting<Boolean>   entityPulse  = bool("Pulse", false).setPage("General").setVisibility(v -> chamsMode.getValue() != ChamsMode.Texture);
    public Setting<Boolean>   hideModel    = bool("Hide Model", true).setPage("General").setVisibility(v -> chamsMode.getValue() != ChamsMode.Texture);

    // Damage Settings Page
    public Setting<Boolean>   damageModify = bool("Damage Modify", false).setPage("Damage").setVisibility(v -> chamsMode.getValue() != ChamsMode.Texture);
    public Setting<Color>     damageColor  = color("Damage Color", 255, 0, 0, 255).setPage("Damage").setVisibility(v -> damageModify.getValue() && chamsMode.getValue() != ChamsMode.Texture);

    // Friend Settings Page
    public Setting<FriendMode> friendMode         = mode("Friend Mode", FriendMode.Default).setPage("Friend").setVisibility(v -> chamsMode.getValue() != ChamsMode.Texture);
    public Setting<Color>      friendFillColor    = color("Friend Fill Color", 85, 255, 255, 100).setPage("Friend").setVisibility(v -> friendMode.getValue() == FriendMode.Custom && (chamsMode.getValue() == ChamsMode.Fill || chamsMode.getValue() == ChamsMode.Both));
    public Setting<Color>      friendOutlineColor = color("Friend Outline Color", 85, 255, 255, 255).setPage("Friend").setVisibility(v -> friendMode.getValue() == FriendMode.Custom && (chamsMode.getValue() == ChamsMode.Outline || chamsMode.getValue() == ChamsMode.Both));

    // Crystals Settings Page
    public Setting<Boolean>     crystalPulse        = bool("Crystal Pulse", false).setPage("Crystals");
    public Setting<CrystalMode> crystalMode         = mode("Crystal Mode", CrystalMode.Both).setPage("Crystals");
    public Setting<Color>       crystalFillColor    = color("Crystal Fill Color", 255, 50, 255, 100).setPage("Crystals").setVisibility(v -> !syncColor.getValue() && (crystalMode.getValue() == CrystalMode.Fill || crystalMode.getValue() == CrystalMode.Both));
    public Setting<Color>       crystalOutlineColor = color("Crystal Outline Color", 255, 50, 255, 255).setPage("Crystals").setVisibility(v -> !syncColor.getValue() && (crystalMode.getValue() == CrystalMode.Outline || crystalMode.getValue() == CrystalMode.Both));

    // Entity Filters Page
    public Setting<Boolean> players      = bool("Players",     true).setPage("Entities");
    public Setting<Float>   playersRange = num("Players Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> players.getValue());

    public Setting<Boolean> monsters      = bool("Monsters",    false).setPage("Entities");
    public Setting<Float>   monstersRange = num("Monsters Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> monsters.getValue());

    public Setting<Boolean> animals      = bool("Animals",     false).setPage("Entities");
    public Setting<Float>   animalsRange = num("Animals Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> animals.getValue());

    public Setting<Boolean> items      = bool("Items",        true).setPage("Entities");
    public Setting<Float>   itemsRange = num("Items Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> items.getValue());

    public Setting<Boolean> crystals      = bool("Crystals",     false).setPage("Entities");
    public Setting<Float>   crystalsRange = num("Crystals Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> crystals.getValue());

    public Setting<Boolean> projectiles      = bool("Projectiles",  false).setPage("Entities");
    public Setting<Float>   projectilesRange = num("Projectiles Range", 64.0f, 4.0f, 256.0f).setPage("Entities").setVisibility(v -> projectiles.getValue());

    public Setting<Boolean> glint            = bool("Glint",        true).setPage("Entities");

    public SeeThroughModule() {
        super("SeeThrough", "Render selected entities through walls", Category.RENDER);
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;

        // Skip flat chams rendering in Texture mode
        if (chamsMode.getValue() == ChamsMode.Texture) return;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player && mc.options.getCameraType().isFirstPerson()) continue;

            // Handle living entities (players, mobs, etc.)
            if (entity instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
                if (!shouldRenderChams(livingEntity, event.getDelta())) continue;

                Color fillCol = fillColor.getValue();
                Color wireCol = outlineColor.getValue();

                if (syncColor.getValue()) {
                    Color ui = Swedenhack.colorManager.get("ui");
                    fillCol = new Color(ui.getRed(), ui.getGreen(), ui.getBlue(), fillCol.getAlpha());
                    wireCol = new Color(ui.getRed(), ui.getGreen(), ui.getBlue(), wireCol.getAlpha());
                }

                boolean isFriend = livingEntity instanceof net.minecraft.world.entity.player.Player &&
                        Swedenhack.friendManager != null &&
                        Swedenhack.friendManager.isFriend(livingEntity.getName().getString());

                if (isFriend) {
                    if (friendMode.getValue() == FriendMode.Custom) {
                        fillCol = friendFillColor.getValue();
                        wireCol = friendOutlineColor.getValue();
                    } else if (friendMode.getValue() == FriendMode.Default) {
                        fillCol = new Color(85, 255, 255, fillCol.getAlpha());
                        wireCol = new Color(85, 255, 255, wireCol.getAlpha());
                    } else if (friendMode.getValue() == FriendMode.Sync) {
                        Color ui = Swedenhack.colorManager.get("ui");
                        fillCol = new Color(ui.getRed(), ui.getGreen(), ui.getBlue(), fillCol.getAlpha());
                        wireCol = new Color(ui.getRed(), ui.getGreen(), ui.getBlue(), wireCol.getAlpha());
                    }
                }

                if (damageModify.getValue() && livingEntity.hurtTime > 0) {
                    fillCol = new Color(damageColor.getValue().getRed(), damageColor.getValue().getGreen(), damageColor.getValue().getBlue(), fillCol.getAlpha());
                    wireCol = new Color(damageColor.getValue().getRed(), damageColor.getValue().getGreen(), damageColor.getValue().getBlue(), wireCol.getAlpha());
                }

                if (entityPulse.getValue()) {
                    fillCol = getPulseColor(fillCol);
                    wireCol = getPulseColor(wireCol);
                }

                Color drawFill = (chamsMode.getValue() == ChamsMode.Outline) ? new Color(0, 0, 0, 0) : fillCol;
                Color drawWire = (chamsMode.getValue() == ChamsMode.Fill) ? new Color(0, 0, 0, 0) : wireCol;

                SeeThroughRender.capturing = true;
                try {
                    WireframeEntityRenderer.render(
                            event.getMatrix(),
                            entity,
                            entity.position(),
                            null, // Do not cache geometry, query fresh coordinates every frame to allow walking/animations
                            event.getDelta(),
                            drawFill,
                            drawWire,
                            lineWidth.getValue()
                    );
                } finally {
                    SeeThroughRender.capturing = false;
                }
            }

            // Handle crystals chams
            if (crystals.getValue() && entity instanceof EndCrystal crystal) {
                if (inRange(entity, crystals, crystalsRange)) {
                    Color fillCol = crystalFillColor.getValue();
                    Color wireCol = crystalOutlineColor.getValue();

                    if (syncColor.getValue()) {
                        Color ui = Swedenhack.colorManager.get("ui");
                        fillCol = new Color(ui.getRed(), ui.getGreen(), ui.getBlue(), fillCol.getAlpha());
                        wireCol = new Color(ui.getRed(), ui.getGreen(), ui.getBlue(), wireCol.getAlpha());
                    }

                    if (crystalPulse.getValue()) {
                        fillCol = getPulseColor(fillCol);
                        wireCol = getPulseColor(wireCol);
                    }

                    Color drawFill = (crystalMode.getValue() == CrystalMode.Outline) ? new Color(0, 0, 0, 0) : fillCol;
                    Color drawWire = (crystalMode.getValue() == CrystalMode.Fill) ? new Color(0, 0, 0, 0) : wireCol;

                    SeeThroughRender.capturing = true;
                    try {
                        WireframeEntityRenderer.render(
                                event.getMatrix(),
                                entity,
                                entity.position(),
                                null, // Do not cache crystal geometry, query fresh coordinates every frame to animate spinning
                                event.getDelta(),
                                drawFill,
                                drawWire,
                                lineWidth.getValue()
                        );
                    } finally {
                        SeeThroughRender.capturing = false;
                    }
                }
            }
        }
    }

    private Color getPulseColor(Color c) {
        double time = (System.currentTimeMillis() / 150.0) * 0.5;
        double sin = Math.sin(time);
        double factor = 0.3 + 0.7 * ((sin + 1.0) / 2.0);
        int alpha = (int) (c.getAlpha() * factor);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private boolean shouldRenderChams(net.minecraft.world.entity.LivingEntity entity, float partialTick) {
        net.minecraft.client.renderer.entity.state.EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(entity, partialTick);
        return shouldSeeThrough(state);
    }

    public boolean shouldSeeThrough(EntityRenderState state) {
        if (state == null) return false;

        EntityType<?> type = state.entityType;
        if (type == null) return false;

        if (type == EntityType.PLAYER)      return inRange(state, players, playersRange);
        if (type == EntityType.ITEM)        return inRange(state, items, itemsRange);
        if (type == EntityType.END_CRYSTAL) return inRange(state, crystals, crystalsRange);
        if (Projectile.class.isAssignableFrom(type.getBaseClass())) return inRange(state, projectiles, projectilesRange);
        if (type.getCategory() == MobCategory.MONSTER) return inRange(state, monsters, monstersRange);
        if (type.getCategory() != MobCategory.MISC)    return inRange(state, animals, animalsRange);
        return false;
    }

    private boolean inRange(EntityRenderState state, Setting<Boolean> enabled, Setting<Float> range) {
        if (!enabled.getValue()) return false;
        float r = range.getValue();
        return state.distanceToCameraSq <= (double) r * r;
    }

    private boolean inRange(Entity entity, Setting<Boolean> enabled, Setting<Float> range) {
        if (!enabled.getValue()) return false;
        float r = range.getValue();
        return mc.player.distanceToSqr(entity) <= (double) r * r;
    }
}
