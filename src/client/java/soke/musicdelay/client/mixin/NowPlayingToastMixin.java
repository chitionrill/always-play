package soke.musicdelay.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.NowPlayingToast;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import soke.musicdelay.client.IMusicManagerMixin;
import soke.musicdelay.client.VanillaTrackRegistry;

@Mixin(NowPlayingToast.class)
public abstract class NowPlayingToastMixin {

    private static final Identifier MDR_BACKGROUND = Identifier.withDefaultNamespace("toast/now_playing");
    private static final Identifier MDR_NOTES = Identifier.parse("icon/music_notes");
    private static final int MDR_TEXT_COLOR = DyeColor.LIGHT_GRAY.getTextColor();

    // Родной метод игры показывает название музыки "в лоб", напрямую переводя технический путь файла —
    // это ломается для музыкальных пластинок (у них название хранится иначе). Подменяем на правильный поиск.
    @Inject(method = "extractToast", at = @At("HEAD"), cancellable = true)
    private static void mdr$fixDiscName(GuiGraphicsExtractor graphics, Font font, CallbackInfo ci) {
        MusicManager manager = Minecraft.getInstance().getMusicManager();
        if (!(manager instanceof IMusicManagerMixin mixin)) return;
        Sound sound = mixin.mdr$getCurrentSound();
        if (sound == null) return;

        Component name = VanillaTrackRegistry.getDisplayNameForLocation(sound.getLocation());
        int width = 30 + font.width(name) + 7;

        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MDR_BACKGROUND, 0, 0, width, 30);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MDR_NOTES, 7, 7, 16, 16, -1);
        graphics.text(font, name, 30, 15 - 9 / 2, MDR_TEXT_COLOR);

        ci.cancel();
    }
}