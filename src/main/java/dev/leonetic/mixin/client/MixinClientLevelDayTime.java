package dev.leonetic.mixin.client;

import dev.leonetic.features.modules.render.WorldVisualsModule;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Implements the ClientLevelDayTimeAccessor interface.
 * Time lock is handled entirely via reflection in WorldVisualsModule.onTick —
 * no @Inject needed here since setDayTime does not exist on ClientLevel in 1.21.11.
 */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevelDayTime implements WorldVisualsModule.ClientLevelDayTimeAccessor {

    @Override
    public void swedenhack$setDayTime(long time) {
        // No-op — time lock is handled in WorldVisualsModule via reflection
    }
}
