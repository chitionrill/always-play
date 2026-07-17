package soke.musicdelay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

public class CustomTrackToast implements Toast {

    private static final Identifier BACKGROUND_SPRITE = Identifier.withDefaultNamespace("toast/now_playing");
    private static final Identifier MUSIC_NOTES_SPRITE = Identifier.parse("icon/music_notes");
    private static final int TEXT_COLOR = DyeColor.LIGHT_GRAY.getTextColor();
    private static final int HEIGHT = 30;
    private static final long BLINK_DURATION_MS = 600L;

    private static CustomTrackToast active = null;

    private final Minecraft minecraft;
    private Component displayText;
    private Component pendingText;
    private boolean blinking = false;
    private long blinkStartMillis = 0L;
    private Toast.Visibility wantedVisibility = Toast.Visibility.SHOW;

    private CustomTrackToast(String trackName) {
        this.minecraft = Minecraft.getInstance();
        this.displayText = Component.literal(trackName);
    }

    // Показывает трек, переиспользуя один и тот же тост вместо создания новых при каждом переключении
    public static void showTrack(String trackName) {
        Component newText = Component.literal(trackName);
        if (active == null) {
            active = new CustomTrackToast(trackName);
            Minecraft.getInstance().gui.toastManager().addToast(active);
        } else if (!active.displayText.equals(newText)) {
            active.pendingText = newText;
            active.blinking = true;
            active.blinkStartMillis = System.currentTimeMillis();
            active.wantedVisibility = Toast.Visibility.HIDE;
        }
    }

    @Override
    public void update(ToastManager manager, long fullyVisibleForMs) {
        if (blinking) {
            if (System.currentTimeMillis() - blinkStartMillis >= BLINK_DURATION_MS) {
                displayText = pendingText;
                blinking = false;
                wantedVisibility = Toast.Visibility.SHOW;
            }
            return;
        }
        double multiplier = manager.getNotificationDisplayTimeMultiplier();
        wantedVisibility = fullyVisibleForMs < 5000.0 * multiplier ? Toast.Visibility.SHOW : Toast.Visibility.HIDE;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE, 0, 0, width(), HEIGHT);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MUSIC_NOTES_SPRITE, 7, 7, 16, 16, -1);
        graphics.text(font, displayText, 30, 15 - 9 / 2, TEXT_COLOR);
    }

    @Override
    public int width() {
        return 30 + minecraft.font.width(displayText) + 7;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    @Override
    public float xPos(int screenWidth, float visiblePortion) {
        return width() * visiblePortion - width();
    }

    @Override
    public float yPos(int firstSlotIndex) {
        return firstSlotIndex * this.height();
    }

    @Override
    public Toast.Visibility getWantedVisibility() {
        return wantedVisibility;
    }

    @Override
    public void onFinishedRendering() {
        if (active == this) {
            active = null;
        }
    }
}