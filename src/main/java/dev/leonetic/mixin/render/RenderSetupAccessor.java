package dev.leonetic.mixin.render;

import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
    @Accessor("textures")
    Map<String, RenderSetup.TextureBinding> swedenhack$getTextures();

    @Accessor("textureTransform")
    TextureTransform swedenhack$getTextureTransform();

    @Accessor("outputTarget")
    OutputTarget swedenhack$getOutputTarget();

    @Accessor("outlineProperty")
    RenderSetup.OutlineProperty swedenhack$getOutlineProperty();

    @Accessor("useLightmap")
    boolean swedenhack$useLightmap();

    @Accessor("useOverlay")
    boolean swedenhack$useOverlay();

    @Accessor("affectsCrumbling")
    boolean swedenhack$affectsCrumbling();

    @Accessor("sortOnUpload")
    boolean swedenhack$sortOnUpload();

    @Accessor("bufferSize")
    int swedenhack$getBufferSize();

    @Accessor("layeringTransform")
    LayeringTransform swedenhack$getLayeringTransform();
}
