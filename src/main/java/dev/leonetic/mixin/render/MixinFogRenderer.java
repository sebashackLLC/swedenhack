package dev.leonetic.mixin.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.leonetic.features.modules.render.NoRenderModule;
import dev.leonetic.features.modules.render.WorldVisualsModule;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.Color;

@Mixin(FogRenderer.class)
public class MixinFogRenderer {
    @Shadow @Final private GpuBuffer emptyBuffer;
    @Shadow @Final private static int FOG_UBO_SIZE;

    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void swedenhack$fogOverride(FogRenderer.FogMode mode, CallbackInfoReturnable<GpuBufferSlice> cir) {
        // NoRender: kill fog entirely
        if (NoRenderModule.isActive(m -> m.noFog.getValue())) {
            cir.setReturnValue(emptyBuffer.slice(0L, FOG_UBO_SIZE));
            return;
        }
        // WorldVisuals: custom fog — handled by the color/distance injections below;
        // we don't cancel here so vanilla fog still draws, just with overridden params.
    }
}
