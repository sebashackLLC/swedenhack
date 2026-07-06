package dev.leonetic.mixin.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.leonetic.Swedenhack;
import dev.leonetic.features.modules.render.ViewModel;
import dev.leonetic.manager.RotationManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static dev.leonetic.util.traits.Util.mc;

@Mixin(LivingEntity.class)
public class MixinLivingEntityTravel {
    @Unique private float swedenhack$origYaw, swedenhack$origPitch;
    @Unique private boolean swedenhack$applied;

    @Inject(method = "travel", at = @At("HEAD"))
    private void swedenhack$travelHead(Vec3 movementInput, CallbackInfo ci) {
        swedenhack$applied = false;

        LivingEntity self = (LivingEntity) (Object) this;
        if (self != mc.player || !swedenhack$spoofing()) return;

        swedenhack$origYaw = self.getYRot();
        swedenhack$origPitch = self.getXRot();
        self.setYRot(Swedenhack.rotationManager.getRotationYaw());
        self.setXRot(Swedenhack.rotationManager.getRotationPitch());
        swedenhack$applied = true;
    }

    @Inject(method = "travel", at = @At("RETURN"))
    private void swedenhack$travelReturn(Vec3 movementInput, CallbackInfo ci) {
        if (!swedenhack$applied) return;
        swedenhack$applied = false;

        LivingEntity self = (LivingEntity) (Object) this;
        self.setYRot(swedenhack$origYaw);
        self.setXRot(swedenhack$origPitch);
    }

    @ModifyExpressionValue(
            method = "jumpFromGround",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"))
    private float swedenhack$jumpYaw(float original) {
        if ((Object) this != mc.player || !swedenhack$spoofing()) return original;
        return Swedenhack.rotationManager.getRotationYaw();
    }

    @Unique
    private boolean swedenhack$spoofing() {
        RotationManager rm = Swedenhack.rotationManager;
        return rm != null && rm.isMoveFixEnabled() && rm.isRotating();
    }

    @ModifyExpressionValue(
            method = "handleRelativeFrictionAndCalculateMovement",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;horizontalCollision:Z"))
    private boolean swedenhack$noScaffoldClimb(boolean original) {
        if ((Object) this != mc.player || !original) return original;
        return !((LivingEntity) (Object) this).getInBlockState().is(Blocks.SCAFFOLDING);
    }

    @Inject(method = "getCurrentSwingDuration", at = @At("HEAD"), cancellable = true)
    private void onGetSwingDuration(CallbackInfoReturnable<Integer> cir) {
        ViewModel vm = ViewModel.getInstance();
        if (vm != null && vm.isEnabled() && vm.swingEnabled()) {
            int original = 6;
            cir.setReturnValue((int) Math.max(1, original / vm.swingSpeed()));
        }
    }
}
