package soke.musicdelay.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** One persistent toast for the local player's custom-record upload. */
public final class RecordUploadToast implements Toast {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/now_playing");
    private static final String PREFIX = "music-delay-reducer.record_upload.";
    private static final int HEIGHT = 42;
    private static RecordUploadToast active;

    private final int width;
    private Component status = text("preparing");
    private int percent = -1;
    private boolean terminal;
    private boolean failed;
    private long finishedAt;
    private Visibility visibility = Visibility.SHOW;

    private RecordUploadToast() {
        Font font = Minecraft.getInstance().font;
        int measured = 200;
        for (String key : new String[]{"title", "preparing", "saving", "success", "failure"})
            measured = Math.max(measured, font.width(text(key)) + 16);
        width = Math.max(measured, font.width(text("progress", 100)) + 16);
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(PREFIX + key, args);
    }

    public static void begin() {
        clear();
        active = new RecordUploadToast();
        Minecraft.getInstance().gui.toastManager().addToast(active);
    }

    public static void progress(int value) {
        if (active == null || active.terminal) return;
        active.percent = Math.clamp(value, 0, 100);
        active.status = text("progress", active.percent);
    }

    public static void saving() {
        if (active == null || active.terminal) return;
        active.percent = 100;
        active.status = text("saving");
    }

    public static void succeeded() { finish(false); }
    public static void failed() { finish(true); }

    private static void finish(boolean failure) {
        if (active == null || active.terminal) return;
        active.terminal = true;
        active.failed = failure;
        if (!failure) active.percent = 100;
        active.status = text(failure ? "failure" : "success");
        active.finishedAt = System.nanoTime();
    }

    public static void clear() {
        if (active != null) active.visibility = Visibility.HIDE;
        active = null;
    }

    @Override
    public void update(ToastManager manager, long fullyVisibleForMs) {
        if (visibility == Visibility.HIDE) return;
        if (terminal && System.nanoTime() - finishedAt >=
                5_000_000_000.0 * manager.getNotificationDisplayTimeMultiplier()) visibility = Visibility.HIDE;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, width, HEIGHT);
        graphics.text(font, text("title"), 8, 7, 0xFFFFFFFF);
        graphics.text(font, status, 8, 20, failed ? 0xFFFF9999 : 0xFFCCCCCC);
        int barWidth = width - 16;
        graphics.fill(8, 34, width - 8, 37, 0xFF444444);
        int color = failed ? 0xFFFF7777 : terminal ? 0xFF77CC88 : 0xFF77AAFF;
        if (percent < 0 && !terminal) {
            int segment = Math.max(1, barWidth / 5);
            int offset = (int) ((fullyVisibleForMs % 1200) * (barWidth - segment) / 1200);
            graphics.fill(8 + offset, 34, 8 + offset + segment, 37, color);
        } else {
            graphics.fill(8, 34, 8 + barWidth * Math.max(0, percent) / 100, 37, color);
        }
    }

    @Override public int width() { return width; }
    @Override public int height() { return HEIGHT; }
    @Override public float xPos(int screenWidth, float visiblePortion) { return width * visiblePortion - width; }
    @Override public float yPos(int firstSlotIndex) { return firstSlotIndex * HEIGHT; }
    @Override public Visibility getWantedVisibility() { return visibility; }
    @Override public void onFinishedRendering() { if (active == this) active = null; }
}