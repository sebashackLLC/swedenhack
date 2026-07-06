package dev.leonetic.mixin.entity;

import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketEntityAccessor {

    @Accessor("life")
    int swedenhack$getLife();

    @Accessor("lifetime")
    int swedenhack$getLifetime();
}
