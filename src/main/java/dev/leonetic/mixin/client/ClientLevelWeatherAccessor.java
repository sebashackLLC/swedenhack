package dev.leonetic.mixin.client;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Level.class)
public interface ClientLevelWeatherAccessor {
    @Accessor("rainLevel")   void swedenhack$setRainLevel(float v);
    @Accessor("oRainLevel")  void swedenhack$setORainLevel(float v);
    @Accessor("thunderLevel")  void swedenhack$setThunderLevel(float v);
    @Accessor("oThunderLevel") void swedenhack$setOThunderLevel(float v);
}
