package soke.musicdelay.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Shown only to the player who clicked a jukebox whose listeners are not ready. */
public final class JukeboxLoadingToast implements Toast {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/now_playing");
    private static final String PREFIX = "music-delay-reducer.jukebox_loading.";
    private static JukeboxLoadingToast active;
    private final String id;
    private final int width;
    private int percent;
    private long changedAt = System.nanoTime();
    private long heardAt = System.nanoTime();
    private Visibility visibility = Visibility.SHOW;

    private JukeboxLoadingToast(String id) {
        this.id = id;
        Font font = Minecraft.getInstance().font;
        width = Math.max(220, Math.max(font.width(text("progress", 100)),
                Math.max(font.width(text("ready")), Math.max(font.width(text("failed")), font.width(text("title"))))) + 16);
    }

    private static Component text(String key, Object... args) { return Component.translatable(PREFIX + key, args); }

    public static void show(String id, int value) {
        int percent = Math.clamp(value, -1, 100);
        if (active == null || !active.id.equals(id) || active.visibility == Visibility.HIDE) {
            clearAll(); active = new JukeboxLoadingToast(id);
            Minecraft.getInstance().gui.toastManager().addToast(active);
        }
        if (active.percent != percent) active.changedAt = System.nanoTime();
        active.percent = percent; active.heardAt = System.nanoTime();
    }

    public static void clear(String id) { if (active != null && active.id.equals(id)) clearAll(); }
    public static void clearAll() {
        if (active != null) active.visibility = Visibility.HIDE;
        active = null;
    }

    @Override public void update(ToastManager manager, long fullyVisibleForMs) {
        if (visibility == Visibility.HIDE) return;
        long now = System.nanoTime();
        if (percent < 0 || percent == 100) {
            if (now - changedAt >= 5_000_000_000.0 * manager.getNotificationDisplayTimeMultiplier()) visibility = Visibility.HIDE;
        } else if (now - heardAt >= 15_000_000_000L) {
            percent = -1; changedAt = now;
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, width, height());
        graphics.text(font, text("title"), 8, 7, 0xFFFFFFFF);
        Component status = percent < 0 ? text("failed") : percent == 100 ? text("ready") : text("progress", percent);
        graphics.text(font, status, 8, 20, percent < 0 ? 0xFFFF9999 : 0xFFCCCCCC);
        graphics.fill(8, 34, width - 8, 37, 0xFF444444);
        graphics.fill(8, 34, 8 + (width - 16) * Math.max(0, percent) / 100, 37,
                percent < 0 ? 0xFFFF7777 : percent == 100 ? 0xFF77CC88 : 0xFF77AAFF);
    }

    @Override public int width() { return width; }
    @Override public int height() { return 42; }
    @Override public float xPos(int screenWidth, float visiblePortion) { return width * visiblePortion - width; }
    @Override public float yPos(int firstSlotIndex) { return firstSlotIndex * height(); }
    @Override public Visibility getWantedVisibility() { return visibility; }
    @Override public void onFinishedRendering() { if (active == this) active = null; }
}
