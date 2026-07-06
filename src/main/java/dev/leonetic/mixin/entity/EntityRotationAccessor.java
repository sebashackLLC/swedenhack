package dev.leonetic.mixin.entity;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LocalPlayer.class)
public interface EntityRotationAccessor {
    @Accessor("xBob")
    float swedenhack$getXBob();

    @Accessor("xBob")
    void swedenhack$setXBob(float value);

    @Accessor("xBobO")
    float swedenhack$getXBobO();

    @Accessor("xBobO")
    void swedenhack$setXBobO(float value);

    @Accessor("yBob")
    float swedenhack$getYBob();

    @Accessor("yBob")
    void swedenhack$setYBob(float value);

    @Accessor("yBobO")
    float swedenhack$getYBobO();

    @Accessor("yBobO")
    void swedenhack$setYBobO(float value);

    @Accessor("lastOnGround")
    boolean swedenhack$getLastOnGround();
}
