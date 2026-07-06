package dev.leonetic.util.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.NativeImage;
import dev.leonetic.mixin.render.RenderSetupAccessor;
import dev.leonetic.mixin.render.RenderTypeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class SeeThroughRender {

    public static final int SEED_ORDER_BASE = 100_000;

    public static final int COLOR_ORDER_BASE = 200_000;

    public static boolean active;

    public static boolean cloning;

    public static boolean capturing;

    public static int cloneSubOrder;

    public static net.minecraft.client.renderer.state.CameraRenderState lastCameraState;

    private static final ConcurrentHashMap<RenderType, RenderType> SEED_CACHE_SOLID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RenderType, RenderType> SEED_CACHE_WIREFRAME = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<RenderType, RenderType> COLOR_CACHE_TEXTURE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RenderType, RenderType> COLOR_CACHE_FILL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RenderType, RenderType> COLOR_CACHE_WIREFRAME = new ConcurrentHashMap<>();

    private static Identifier fillTextureId = null;
    private static DynamicTexture fillTexture = null;
    private static Identifier wireTextureId = null;
    private static DynamicTexture wireTexture = null;

    private SeeThroughRender() {}

    private static int toABGR(java.awt.Color color) {
        int r = color.getRed();
        int g = color.getGreen();
        int b = color.getBlue();
        int a = color.getAlpha();
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    public static void updateTextureColors(java.awt.Color fillColorVal, java.awt.Color wireColorVal) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        if (fillColorVal != null) {
            int fillABGR = toABGR(fillColorVal);
            if (fillTexture == null) {
                NativeImage img = new NativeImage(1, 1, false);
                img.setPixelABGR(0, 0, fillABGR);
                fillTexture = new DynamicTexture(() -> "seethrough_fill", img);
                fillTexture.upload();
                fillTextureId = Identifier.fromNamespaceAndPath("swedenhack", "seethrough_fill");
                mc.getTextureManager().register(fillTextureId, fillTexture);
            } else {
                fillTexture.getPixels().setPixelABGR(0, 0, fillABGR);
                fillTexture.upload();
            }
        }

        if (wireColorVal != null) {
            int wireABGR = toABGR(wireColorVal);
            if (wireTexture == null) {
                NativeImage img = new NativeImage(1, 1, false);
                img.setPixelABGR(0, 0, wireABGR);
                wireTexture = new DynamicTexture(() -> "seethrough_wire", img);
                wireTexture.upload();
                wireTextureId = Identifier.fromNamespaceAndPath("swedenhack", "seethrough_wire");
                mc.getTextureManager().register(wireTextureId, wireTexture);
            } else {
                wireTexture.getPixels().setPixelABGR(0, 0, wireABGR);
                wireTexture.upload();
            }
        }
    }

    public static void begin() {
        active = true;
        cloneSubOrder = 0;

        dev.leonetic.features.modules.render.SeeThroughModule seeThrough = dev.leonetic.Swedenhack.moduleManager == null
                ? null
                : dev.leonetic.Swedenhack.moduleManager.getModuleByClass(dev.leonetic.features.modules.render.SeeThroughModule.class);
        if (seeThrough != null && seeThrough.isEnabled()) {
            updateTextureColors(seeThrough.fillColor.getValue(), seeThrough.outlineColor.getValue());
        }
    }

    public static void end() {
        active = false;
    }

    public static boolean isGlintType(RenderType type) {
        if (type == null) return false;
        String name = type.toString().toLowerCase(Locale.ROOT);
        return name.contains("glint") || name.contains("foil");
    }

    public static RenderType wrapDepthSeed(RenderType original, boolean wireframe) {
        if (original == null) return null;
        var cache = wireframe ? SEED_CACHE_WIREFRAME : SEED_CACHE_SOLID;
        return cache.computeIfAbsent(original, o ->
                build(o, wireframe ? "_swedenhack_seethrough_seed_wf" : "_swedenhack_seethrough_seed",
                        DepthTestFunction.GREATER_DEPTH_TEST, true, false, Boolean.FALSE, null,
                        wireframe ? com.mojang.blaze3d.platform.PolygonMode.WIREFRAME : com.mojang.blaze3d.platform.PolygonMode.FILL));
    }

    public static RenderType wrapColorTexture(RenderType original) {
        if (original == null) return null;
        return COLOR_CACHE_TEXTURE.computeIfAbsent(original, o ->
                build(o, "_swedenhack_seethrough_texture",
                        DepthTestFunction.LEQUAL_DEPTH_TEST, true, true, null, null,
                        com.mojang.blaze3d.platform.PolygonMode.FILL));
    }

    public static RenderType wrapColorFill(RenderType original) {
        if (original == null) return null;
        return COLOR_CACHE_FILL.computeIfAbsent(original, o ->
                build(o, "_swedenhack_seethrough_fill",
                        DepthTestFunction.LEQUAL_DEPTH_TEST, true, true, null, fillTextureId,
                        com.mojang.blaze3d.platform.PolygonMode.FILL));
    }

    public static RenderType wrapColorWireframe(RenderType original) {
        if (original == null) return null;
        return COLOR_CACHE_WIREFRAME.computeIfAbsent(original, o ->
                build(o, "_swedenhack_seethrough_wire",
                        DepthTestFunction.LEQUAL_DEPTH_TEST, true, true, null, wireTextureId,
                        com.mojang.blaze3d.platform.PolygonMode.WIREFRAME));
    }

    private static RenderType build(RenderType original, String suffix, DepthTestFunction depthFunc,
                                    boolean depthWrite, boolean colorWrite, Boolean cullOverride,
                                    Identifier overrideTexture,
                                    com.mojang.blaze3d.platform.PolygonMode polygonMode) {
        try {
            RenderSetup origSetup = ((RenderTypeAccessor) (Object) original).swedenhack$getState();
            RenderSetupAccessor sa = (RenderSetupAccessor) (Object) origSetup;

            RenderPipeline pipeline = clonePipeline(original.pipeline(), depthFunc, depthWrite, colorWrite, cullOverride, polygonMode);

            RenderSetup.RenderSetupBuilder b = RenderSetup.builder(pipeline);
            for (Map.Entry<String, RenderSetup.TextureBinding> e : sa.swedenhack$getTextures().entrySet()) {
                RenderSetup.TextureBinding binding = e.getValue();
                Identifier location = (overrideTexture != null && "Sampler0".equals(e.getKey())) ? overrideTexture : binding.location();
                if (binding.sampler() != null) {
                    b.withTexture(e.getKey(), location, binding.sampler());
                } else {
                    b.withTexture(e.getKey(), location);
                }
            }
            if (sa.swedenhack$useLightmap()) b.useLightmap();
            if (sa.swedenhack$useOverlay()) b.useOverlay();
            if (sa.swedenhack$affectsCrumbling()) b.affectsCrumbling();
            if (sa.swedenhack$sortOnUpload()) b.sortOnUpload();
            b.bufferSize(sa.swedenhack$getBufferSize());
            b.setLayeringTransform(sa.swedenhack$getLayeringTransform());
            b.setTextureTransform(sa.swedenhack$getTextureTransform());
            b.setOutline(sa.swedenhack$getOutlineProperty());

            return RenderType.create(original.toString() + suffix, b.createRenderSetup());
        } catch (Throwable t) {
            return original;
        }
    }

    private static RenderPipeline clonePipeline(RenderPipeline orig, DepthTestFunction depthFunc,
                                                boolean depthWrite, boolean colorWrite, Boolean cullOverride,
                                                com.mojang.blaze3d.platform.PolygonMode polygonMode) {
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(orig.getLocation())
                .withVertexShader(orig.getVertexShader())
                .withFragmentShader(orig.getFragmentShader())
                .withDepthTestFunction(depthFunc)
                .withPolygonMode(polygonMode)
                .withCull(cullOverride != null ? cullOverride : orig.isCull())
                .withColorWrite(colorWrite && orig.isWriteColor(), colorWrite && orig.isWriteAlpha())
                .withDepthWrite(depthWrite)
                .withColorLogic(orig.getColorLogic())
                .withVertexFormat(orig.getVertexFormat(), orig.getVertexFormatMode())
                .withDepthBias(orig.getDepthBiasScaleFactor(), orig.getDepthBiasConstant());

        Optional<BlendFunction> blend = orig.getBlendFunction();
        if (blend.isPresent()) b.withBlend(blend.get());
        else b.withoutBlend();

        for (String s : orig.getSamplers()) b.withSampler(s);
        for (RenderPipeline.UniformDescription u : orig.getUniforms()) {
            if (u.textureFormat != null) {
                b.withUniform(u.name, u.type, u.textureFormat);
            } else {
                b.withUniform(u.name, u.type);
            }
        }

        ShaderDefines defs = orig.getShaderDefines();
        for (Map.Entry<String, String> e : defs.values().entrySet()) {
            String name = e.getKey();
            String value = e.getValue();
            try {
                b.withShaderDefine(name, Integer.parseInt(value));
            } catch (NumberFormatException nfe1) {
                try {
                    b.withShaderDefine(name, Float.parseFloat(value));
                } catch (NumberFormatException ignored) {

                }
            }
        }
        for (String flag : defs.flags()) b.withShaderDefine(flag);

        return b.build();
    }
}
