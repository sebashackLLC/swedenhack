package dev.leonetic.mixin.client;

import dev.leonetic.util.player.ChatUtil;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChatComponent.class)
public abstract class MixinChatComponent {

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
        at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
        argsOnly = true
    )
    private net.minecraft.network.chat.Component swedenhack$modifyIncomingChatMessage(net.minecraft.network.chat.Component message) {
        String text = message.getString();
        
        // Ignore messages with [] (e.g. [inaktiverad], [VIP], etc.)
        if (containsBrackets(text)) {
            return message;
        }

        if (text.startsWith("[Translate]")) {
            return message;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return message;

        String myName = mc.getUser().getName();
        if (text.contains(myName)) {
            if (!dev.leonetic.features.modules.player.TranslatorModule.translationCache.isEmpty()) {
                for (java.util.Map.Entry<String, String> entry : dev.leonetic.features.modules.player.TranslatorModule.translationCache.entrySet()) {
                    String translated = entry.getKey();
                    if (text.contains(translated)) {
                        String english = entry.getValue();
                        net.minecraft.network.chat.MutableComponent suffix = net.minecraft.network.chat.Component.literal(" (" + english + ")").withStyle(net.minecraft.ChatFormatting.GRAY);
                        return message.copy().append(suffix);
                    }
                }
            }
        } else {
            dev.leonetic.features.modules.player.TranslatorModule translator = dev.leonetic.Swedenhack.moduleManager != null
                ? dev.leonetic.Swedenhack.moduleManager.getModuleByClass(dev.leonetic.features.modules.player.TranslatorModule.class)
                : null;
            if (translator != null && translator.isEnabled()) {
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    dev.leonetic.features.modules.player.TranslatorModule.TranslationResult result =
                        dev.leonetic.features.modules.player.TranslatorModule.translateToEnglish(text);
                    if (result != null && "sv".equals(result.detectedLanguage)) {
                        if (!result.translatedText.equalsIgnoreCase(text)) {
                            mc.execute(() -> {
                                if (mc.gui != null && mc.gui.getChat() != null) {
                                    mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal("\u00A77[Translate] " + result.translatedText));
                                }
                            });
                        }
                    }
                });
            }
        }
        return message;
    }

    @Unique
    private boolean containsBrackets(String text) {
        if (text == null || text.isEmpty()) return false;
        String trimmed = text.trim();
        return trimmed.contains("[") || trimmed.contains("]");
    }

    @Unique private static final double swedenhack$SPEED = 12.0;
    @Unique private static final int swedenhack$MAX_LINES = 3;

    @Unique private boolean swedenhack$pushed;
    @Unique private float swedenhack$offset;
    @Unique private GuiMessage.Line swedenhack$lastHead;
    @Unique private long swedenhack$lastTimeMs;

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V",
            at = @At("HEAD")
    )
    private void swedenhack$slideIn(GuiGraphics graphics, Font font, int tickCount, int mouseX, int mouseY,
                                  boolean focused, boolean bl, CallbackInfo ci) {
        swedenhack$pushed = false;

        float dy = swedenhack$update(((ChatComponentAccessor) this).swedenhack$getTrimmedMessages());
        if (dy != 0f) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(0f, dy);
            swedenhack$pushed = true;
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V",
            at = @At("RETURN")
    )
    private void swedenhack$slideInEnd(GuiGraphics graphics, Font font, int tickCount, int mouseX, int mouseY,
                                     boolean focused, boolean bl, CallbackInfo ci) {
        if (swedenhack$pushed) {
            graphics.pose().popMatrix();
            swedenhack$pushed = false;
        }
    }

    @Unique
    private float swedenhack$update(List<GuiMessage.Line> lines) {
        long now = System.currentTimeMillis();

        float dt = Math.min((now - swedenhack$lastTimeMs) / 1000f, 0.1f);
        swedenhack$lastTimeMs = now;

        GuiMessage.Line head = lines.isEmpty() ? null : lines.get(0);
        if (head != swedenhack$lastHead) {

            if (swedenhack$lastHead != null && head != ChatUtil.noAnimateHead) {

                int newLines = 0;
                for (GuiMessage.Line line : lines) {
                    if (line == swedenhack$lastHead) break;
                    newLines++;
                }
                float pitch = swedenhack$linePitch();
                swedenhack$offset = Math.min(swedenhack$offset + newLines * pitch, swedenhack$MAX_LINES * pitch);
            }
            swedenhack$lastHead = head;
        }

        swedenhack$offset *= (float) Math.exp(-swedenhack$SPEED * dt);
        if (swedenhack$offset < 0.5f) {
            swedenhack$offset = 0f;
        }
        return swedenhack$offset;
    }

    @Unique
    private float swedenhack$linePitch() {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.options.chatScale().get();
        double spacing = mc.options.chatLineSpacing().get();
        return (float) (9.0 * (spacing + 1.0) * scale);
    }
}