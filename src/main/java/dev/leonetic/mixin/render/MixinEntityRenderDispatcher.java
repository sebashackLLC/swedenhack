package dev.leonetic.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.leonetic.Swedenhack;
import dev.leonetic.features.modules.render.SeeThroughModule;
import dev.leonetic.util.render.SeeThroughRender;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V"
        )
    )
    private void swedenhack$seeThroughSubmit(EntityRenderer renderer, EntityRenderState state, PoseStack poseStack,
                                           SubmitNodeCollector collector, CameraRenderState camera) {
        if (camera != null) {
            SeeThroughRender.lastCameraState = camera;
        }

        boolean apply = false;
        boolean cancelOriginal = false;
        
        SeeThroughModule seeThrough = Swedenhack.moduleManager == null
                ? null
                : Swedenhack.moduleManager.getModuleByClass(SeeThroughModule.class);
                
        if (seeThrough != null && seeThrough.isEnabled() && seeThrough.shouldSeeThrough(state) && !SeeThroughRender.capturing) {
            if (seeThrough.chamsMode.getValue() == SeeThroughModule.ChamsMode.Texture) {
                apply = true;
                SeeThroughRender.begin();
            } else {
                SeeThroughRender.active = false;
                if (seeThrough.hideModel.getValue()) {
                    cancelOriginal = true;
                }
            }
        } else {
            SeeThroughRender.active = false;
        }
        
        if (!cancelOriginal) {
            try {
                renderer.submit(state, poseStack, collector, camera);
            } finally {
                if (apply) {
                    SeeThroughRender.end();
                }
            }
        }
    }
}
