package dev.leonetic.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.leonetic.Swedenhack;
import dev.leonetic.features.modules.render.CrystalHandModule;
import dev.leonetic.features.modules.render.NoRenderModule;
import dev.leonetic.features.modules.render.ShadersModule;
import dev.leonetic.features.modules.render.ViewModel;
import dev.leonetic.mixin.entity.EntityRotationAccessor;
import dev.leonetic.util.render.HandShaderRender;
import dev.leonetic.util.render.HandSilhouetteCollector;
import dev.leonetic.util.render.TeeSubmitCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class MixinItemInHandRenderer {

    @Shadow private float mainHandHeight;
    @Shadow private float offHandHeight;
    @Shadow private float oMainHandHeight;
    @Shadow private float oOffHandHeight;

    @Unique
    private boolean handShader$capturing;

    private float noSway$savedXBob;
    private float noSway$savedXBobO;
    private float noSway$savedYBob;
    private float noSway$savedYBobO;

    @Inject(method = "tick", at = @At("TAIL"))
    private void viewModel$tick(CallbackInfo ci) {
        ViewModel vm = ViewModel.getInstance();
        if (vm != null && vm.hideSwapping()) {
            this.mainHandHeight = 1.0f;
            this.offHandHeight = 1.0f;
            this.oMainHandHeight = 1.0f;
            this.oOffHandHeight = 1.0f;
        }
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void viewModel$renderArmWithItem(net.minecraft.client.player.AbstractClientPlayer player, float partialTicks, float pitch, net.minecraft.world.InteractionHand hand, float swingProgress, net.minecraft.world.item.ItemStack stack, float equipProgress, PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        ViewModel vm = ViewModel.getInstance();
        if (vm != null && vm.isEnabled()) {
            vm.applyTransforms(poseStack, partialTicks);
            float px = vm.getPosX();
            if (px != 0) {
                boolean isLeftHand = (hand == net.minecraft.world.InteractionHand.OFF_HAND) ^ (player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT);
                poseStack.translate(isLeftHand ? -px : px, 0, 0);
            }
        }
    }

    @Inject(method = "applyItemArmAttackTransform", at = @At("HEAD"), cancellable = true)
    private void viewModel$applyItemArmAttackTransform(PoseStack poseStack, net.minecraft.world.entity.HumanoidArm arm, float swingProgress, CallbackInfo ci) {
        ViewModel vm = ViewModel.getInstance();
        if (vm != null && vm.isEnabled() && vm.swingEnabled()) {
            ci.cancel();
            int i = arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1;
            float f = net.minecraft.util.Mth.sin(swingProgress * swingProgress * (float)Math.PI);
            float g = net.minecraft.util.Mth.sin(net.minecraft.util.Mth.sqrt(swingProgress) * (float)Math.PI);

            float rotMult = vm.swingRotation();

            if (!vm.oldAnimations()) {
                poseStack.translate((float)i * -0.4F * f, 0.4F * g, -0.3F * f);
            }

            float swing = net.minecraft.util.Mth.sin(swingProgress * (float)Math.PI);
            poseStack.translate(0, vm.swingYOffset() * swing, vm.swingZOffset() * swing);

            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float)i * (45.0F + f * -20.0F * rotMult)));
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float)i * g * -20.0F * rotMult));
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(g * -80.0F * rotMult));
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float)i * -45.0F));
        }
    }

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void noSway$pre(CallbackInfo ci) {
        ViewModel vm = ViewModel.getInstance();
        boolean noSway = NoRenderModule.isActive(m -> m.noSway.getValue()) || (vm != null && vm.noSway());
        if (!noSway) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        EntityRotationAccessor acc = (EntityRotationAccessor) player;
        noSway$savedXBob = acc.swedenhack$getXBob();
        noSway$savedXBobO = acc.swedenhack$getXBobO();
        noSway$savedYBob = acc.swedenhack$getYBob();
        noSway$savedYBobO = acc.swedenhack$getYBobO();
        float xRot = player.getXRot();
        float yRot = player.getYRot();
        acc.swedenhack$setXBob(xRot);
        acc.swedenhack$setXBobO(xRot);
        acc.swedenhack$setYBob(yRot);
        acc.swedenhack$setYBobO(yRot);
    }

    @Inject(method = "renderHandsWithItems", at = @At("RETURN"))
    private void noSway$post(CallbackInfo ci) {
        ViewModel vm = ViewModel.getInstance();
        boolean noSway = NoRenderModule.isActive(m -> m.noSway.getValue()) || (vm != null && vm.noSway());
        if (!noSway) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        EntityRotationAccessor acc = (EntityRotationAccessor) player;
        acc.swedenhack$setXBob(noSway$savedXBob);
        acc.swedenhack$setXBobO(noSway$savedXBobO);
        acc.swedenhack$setYBob(noSway$savedYBob);
        acc.swedenhack$setYBobO(noSway$savedYBobO);
    }

    @ModifyArg(
        method = "renderHandsWithItems",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"
        ),
        index = 5
    )
    private ItemStack crystalHand$modifyRenderedItem(ItemStack original) {
        CrystalHandModule mod = Swedenhack.moduleManager.getModuleByClass(CrystalHandModule.class);
        if (mod == null || !mod.isEnabled()) return original;
        return mod.getDisplayStack(original);
    }

    @ModifyVariable(method = "renderHandsWithItems", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private SubmitNodeCollector handShader$wrapCollector(SubmitNodeCollector original) {
        handShader$capturing = false;
        ShadersModule mod = Swedenhack.moduleManager.getModuleByClass(ShadersModule.class);
        if (mod == null || !mod.wantsHandShader()
                || (!mod.handOutline.getValue() && !mod.handFill.getValue())) {
            return original;
        }
        HandSilhouetteCollector secondary = HandShaderRender.beginCapture(mod.getHandRgb());
        if (secondary == null) return original;

        handShader$capturing = true;
        HandShaderRender.capturePaused = false;
        return new TeeSubmitCollector(original, secondary);
    }

    @Inject(method = "renderHandsWithItems", at = @At("TAIL"))
    private void handShader$flush(float partialTick, PoseStack poseStack, SubmitNodeCollector collector,
                                  LocalPlayer player, int light, CallbackInfo ci) {
        if (!handShader$capturing) return;
        handShader$capturing = false;
        HandShaderRender.capturePaused = false;
        HandShaderRender.flush();
    }
}