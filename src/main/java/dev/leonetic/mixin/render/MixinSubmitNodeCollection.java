package dev.leonetic.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.leonetic.util.render.SeeThroughRender;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(SubmitNodeCollection.class)
public class MixinSubmitNodeCollection {

    @Shadow @Final private SubmitNodeStorage submitNodeStorage;

    @Unique
    private boolean swedenhack$isGlintEnabled() {
        dev.leonetic.features.modules.render.SeeThroughModule seeThrough = dev.leonetic.Swedenhack.moduleManager == null
                ? null
                : dev.leonetic.Swedenhack.moduleManager.getModuleByClass(dev.leonetic.features.modules.render.SeeThroughModule.class);
        return seeThrough != null && seeThrough.isEnabled() && seeThrough.glint.getValue();
    }

    @Unique
    private dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode swedenhack$getChamsMode() {
        dev.leonetic.features.modules.render.SeeThroughModule seeThrough = dev.leonetic.Swedenhack.moduleManager == null
                ? null
                : dev.leonetic.Swedenhack.moduleManager.getModuleByClass(dev.leonetic.features.modules.render.SeeThroughModule.class);
        return seeThrough != null && seeThrough.isEnabled() ? seeThrough.chamsMode.getValue() : dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Texture;
    }

    @Inject(method = "submitModel", at = @At("RETURN"))
    private <S> void swedenhack$cloneModelForSeeThrough(Model<? super S> model, S modelState, PoseStack poseStack,
                                                       RenderType renderType, int packedLight, int overlay,
                                                       int blockPosition, TextureAtlasSprite sprite, int flags,
                                                       ModelFeatureRenderer.CrumblingOverlay crumbling,
                                                       CallbackInfo ci) {
        if (SeeThroughRender.capturing) return;
        if (!SeeThroughRender.active) return;
        boolean isGlint = SeeThroughRender.isGlintType(renderType);
        if (isGlint && !swedenhack$isGlintEnabled()) return;
        if (SeeThroughRender.cloning && !isGlint) return;

        int sub = SeeThroughRender.cloneSubOrder++;
        boolean wasCloning = SeeThroughRender.cloning;
        SeeThroughRender.cloning = true;
        try {
            dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode chamsMode = swedenhack$getChamsMode();
            boolean drawSolid = chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Texture
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Fill
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Both;
            boolean drawWire = chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Outline
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Both;

            if (!isGlint) {
                RenderType seed = SeeThroughRender.wrapDepthSeed(renderType, !drawSolid);
                submitNodeStorage.order(SeeThroughRender.SEED_ORDER_BASE + sub).submitModel(
                        model, modelState, poseStack, seed, packedLight, overlay, blockPosition, sprite, flags, crumbling);
            }
            if (drawSolid) {
                RenderType drawType;
                if (isGlint) {
                    drawType = renderType;
                } else if (chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Texture) {
                    drawType = SeeThroughRender.wrapColorTexture(renderType);
                } else {
                    drawType = SeeThroughRender.wrapColorFill(renderType);
                }
                submitNodeStorage.order(SeeThroughRender.COLOR_ORDER_BASE + sub).submitModel(
                        model, modelState, poseStack, drawType, packedLight, overlay, blockPosition, sprite, flags, crumbling);
            }
            if (drawWire && !isGlint) {
                RenderType drawType = SeeThroughRender.wrapColorWireframe(renderType);
                submitNodeStorage.order(SeeThroughRender.COLOR_ORDER_BASE + sub + 50_000).submitModel(
                        model, modelState, poseStack, drawType, packedLight, overlay, blockPosition, sprite, flags, crumbling);
            }
        } finally {
            SeeThroughRender.cloning = wasCloning;
        }
    }

    @Inject(method = "submitModelPart", at = @At("RETURN"))
    private void swedenhack$cloneModelPartForSeeThrough(ModelPart part, PoseStack poseStack, RenderType renderType,
                                                       int packedLight, int overlay, TextureAtlasSprite sprite,
                                                       boolean copyNormals, boolean fullBright, int flags,
                                                       ModelFeatureRenderer.CrumblingOverlay crumbling, int data,
                                                       CallbackInfo ci) {
        if (SeeThroughRender.capturing) return;
        if (!SeeThroughRender.active) return;
        boolean isGlint = SeeThroughRender.isGlintType(renderType);
        if (isGlint && !swedenhack$isGlintEnabled()) return;
        if (SeeThroughRender.cloning && !isGlint) return;

        int sub = SeeThroughRender.cloneSubOrder++;
        boolean wasCloning = SeeThroughRender.cloning;
        SeeThroughRender.cloning = true;
        try {
            dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode chamsMode = swedenhack$getChamsMode();
            boolean drawSolid = chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Texture
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Fill
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Both;
            boolean drawWire = chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Outline
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Both;

            if (!isGlint) {
                RenderType seed = SeeThroughRender.wrapDepthSeed(renderType, !drawSolid);
                submitNodeStorage.order(SeeThroughRender.SEED_ORDER_BASE + sub).submitModelPart(
                        part, poseStack, seed, packedLight, overlay, sprite, copyNormals, fullBright, flags, crumbling, data);
            }
            if (drawSolid) {
                RenderType drawType;
                if (isGlint) {
                    drawType = renderType;
                } else if (chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Texture) {
                    drawType = SeeThroughRender.wrapColorTexture(renderType);
                } else {
                    drawType = SeeThroughRender.wrapColorFill(renderType);
                }
                submitNodeStorage.order(SeeThroughRender.COLOR_ORDER_BASE + sub).submitModelPart(
                        part, poseStack, drawType, packedLight, overlay, sprite, copyNormals, fullBright, flags, crumbling, data);
            }
            if (drawWire && !isGlint) {
                RenderType drawType = SeeThroughRender.wrapColorWireframe(renderType);
                submitNodeStorage.order(SeeThroughRender.COLOR_ORDER_BASE + sub + 50_000).submitModelPart(
                        part, poseStack, drawType, packedLight, overlay, sprite, copyNormals, fullBright, flags, crumbling, data);
            }
        } finally {
            SeeThroughRender.cloning = wasCloning;
        }
    }

    @Inject(method = "submitItem", at = @At("RETURN"))
    private void swedenhack$cloneItemForSeeThrough(PoseStack poseStack, ItemDisplayContext context, int packedLight,
                                                 int overlay, int data, int[] tints, List<BakedQuad> quads,
                                                 RenderType renderType, ItemStackRenderState.FoilType foil,
                                                 CallbackInfo ci) {
        if (SeeThroughRender.capturing) return;
        if (!SeeThroughRender.active) return;
        boolean isGlint = SeeThroughRender.isGlintType(renderType);
        if (isGlint && !swedenhack$isGlintEnabled()) return;
        if (SeeThroughRender.cloning && !isGlint) return;

        int sub = SeeThroughRender.cloneSubOrder++;
        boolean wasCloning = SeeThroughRender.cloning;
        SeeThroughRender.cloning = true;
        try {
            dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode chamsMode = swedenhack$getChamsMode();
            boolean drawSolid = chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Texture
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Fill
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Both;
            boolean drawWire = chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Outline
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Both;
            boolean glint = swedenhack$isGlintEnabled();

            if (!isGlint) {
                RenderType seed = SeeThroughRender.wrapDepthSeed(renderType, !drawSolid);
                submitNodeStorage.order(SeeThroughRender.SEED_ORDER_BASE + sub).submitItem(
                        poseStack, context, packedLight, overlay, data, tints, quads, seed,
                        ItemStackRenderState.FoilType.NONE);
            }
            if (drawSolid) {
                RenderType drawType;
                if (isGlint) {
                    drawType = renderType;
                } else if (chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Texture) {
                    drawType = SeeThroughRender.wrapColorTexture(renderType);
                } else {
                    drawType = SeeThroughRender.wrapColorFill(renderType);
                }
                submitNodeStorage.order(SeeThroughRender.COLOR_ORDER_BASE + sub).submitItem(
                        poseStack, context, packedLight, overlay, data, tints, quads, drawType,
                        glint ? foil : ItemStackRenderState.FoilType.NONE);
            }
            if (drawWire && !isGlint) {
                RenderType drawType = SeeThroughRender.wrapColorWireframe(renderType);
                submitNodeStorage.order(SeeThroughRender.COLOR_ORDER_BASE + sub + 50_000).submitItem(
                        poseStack, context, packedLight, overlay, data, tints, quads, drawType,
                        ItemStackRenderState.FoilType.NONE);
            }
        } finally {
            SeeThroughRender.cloning = wasCloning;
        }
    }

    @Inject(method = "submitCustomGeometry", at = @At("RETURN"))
    private void swedenhack$cloneCustomGeometryForSeeThrough(PoseStack poseStack, RenderType renderType,
                                                             net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer,
                                                             CallbackInfo ci) {
        if (SeeThroughRender.capturing) return;
        if (!SeeThroughRender.active) return;
        boolean isGlint = SeeThroughRender.isGlintType(renderType);
        if (isGlint && !swedenhack$isGlintEnabled()) return;
        if (SeeThroughRender.cloning && !isGlint) return;

        int sub = SeeThroughRender.cloneSubOrder++;
        boolean wasCloning = SeeThroughRender.cloning;
        SeeThroughRender.cloning = true;
        try {
            dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode chamsMode = swedenhack$getChamsMode();
            boolean drawSolid = chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Texture
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Fill
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Both;
            boolean drawWire = chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Outline
                    || chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Both;

            if (!isGlint) {
                RenderType seed = SeeThroughRender.wrapDepthSeed(renderType, !drawSolid);
                submitNodeStorage.order(SeeThroughRender.SEED_ORDER_BASE + sub).submitCustomGeometry(
                        poseStack, seed, customGeometryRenderer);
            }
            if (drawSolid) {
                RenderType drawType;
                if (isGlint) {
                    drawType = renderType;
                } else if (chamsMode == dev.leonetic.features.modules.render.SeeThroughModule.ChamsMode.Texture) {
                    drawType = SeeThroughRender.wrapColorTexture(renderType);
                } else {
                    drawType = SeeThroughRender.wrapColorFill(renderType);
                }
                submitNodeStorage.order(SeeThroughRender.COLOR_ORDER_BASE + sub).submitCustomGeometry(
                        poseStack, drawType, customGeometryRenderer);
            }
            if (drawWire && !isGlint) {
                RenderType drawType = SeeThroughRender.wrapColorWireframe(renderType);
                submitNodeStorage.order(SeeThroughRender.COLOR_ORDER_BASE + sub + 50_000).submitCustomGeometry(
                        poseStack, drawType, customGeometryRenderer);
            }
        } finally {
            SeeThroughRender.cloning = wasCloning;
        }
    }
}
