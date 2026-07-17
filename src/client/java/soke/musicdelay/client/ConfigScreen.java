package soke.musicdelay.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import soke.musicdelay.ModConfig;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

public class ConfigScreen extends Screen {

    private static final int MAX_SECONDS = 1200;
    private static final int MAX_SKIP_DELAY_SECONDS = 5;
    private static final int MAX_CROSSFADE_TENTHS = 30;
    private static final int MAX_STARTUP_DELAY_SECONDS = 5;

    private static final int ROW_HEIGHT = 30;
    private static final int CONTENT_TOP = 35;
    private static final int BOTTOM_MARGIN = 10;
    private static final int SCROLL_STEP = 20;
    private static final int TOTAL_ROWS = 12;

    private final @Nullable Screen parent;
    private final ModConfig config;

    private int minSeconds;
    private int maxSeconds;
    private int skipDelaySeconds;
    private String playbackMode;
    private boolean crossfadeEnabled;
    private double crossfadeDurationSeconds;
    private boolean startupFadeEnabled;
    private int startupDelaySeconds;
    private boolean worldRestartEnabled;
    private String trackOrderMode;

    private Button vanillaButton;
    private Button customButton;
    private Button bothButton;
    private Button crossfadeToggleButton;
    private Button startupToggleButton;
    private Button worldRestartToggleButton;
    private Button orderSequentialButton;
    private Button orderShuffleNoRepeatButton;
    private Button orderRandomButton;

    private int scrollAmount = 0;

    public ConfigScreen(@Nullable Screen parent) {
        super(Component.translatable("music-delay-reducer.config.title"));
        this.parent = parent;
        this.config = ModConfig.get();
        this.minSeconds = config.minDelaySeconds;
        this.maxSeconds = config.maxDelaySeconds;
        this.skipDelaySeconds = config.skipDelaySeconds;
        this.playbackMode = config.playbackMode;
        this.crossfadeEnabled = config.crossfadeEnabled;
        this.crossfadeDurationSeconds = config.crossfadeDurationSeconds;
        this.startupFadeEnabled = config.startupFadeEnabled;
        this.startupDelaySeconds = config.startupDelaySeconds;
        this.worldRestartEnabled = config.worldRestartEnabled;
        this.trackOrderMode = config.trackOrderMode;
    }

    private int maxScroll() {
        int contentHeight = TOTAL_ROWS * ROW_HEIGHT;
        int visibleHeight = this.height - CONTENT_TOP - BOTTOM_MARGIN;
        return Math.max(0, contentHeight - visibleHeight);
    }

    private int rowY(int rowIndex) {
        return CONTENT_TOP + rowIndex * ROW_HEIGHT - scrollAmount;
    }

    @Override
    protected void init() {
        scrollAmount = Math.max(0, Math.min(scrollAmount, maxScroll()));
        int centerX = this.width / 2;
        int row = 0;

        vanillaButton = Button.builder(Component.translatable("music-delay-reducer.config.mode_vanilla"), b -> setMode("VANILLA"))
                .bounds(centerX - 100, rowY(row), 64, 20).build();
        customButton = Button.builder(Component.translatable("music-delay-reducer.config.mode_custom"), b -> setMode("CUSTOM"))
                .bounds(centerX - 32, rowY(row), 64, 20).build();
        bothButton = Button.builder(Component.translatable("music-delay-reducer.config.mode_both"), b -> setMode("BOTH"))
                .bounds(centerX + 36, rowY(row), 64, 20).build();

        vanillaButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.mode_vanilla.tooltip")));
        customButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.mode_custom.tooltip")));
        bothButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.mode_both.tooltip")));

        this.addRenderableWidget(vanillaButton);
        this.addRenderableWidget(customButton);
        this.addRenderableWidget(bothButton);
        updateModeButtons();
        row++;

        orderSequentialButton = Button.builder(Component.translatable("music-delay-reducer.config.order_sequential"), b -> setOrder("SEQUENTIAL"))
                .bounds(centerX - 100, rowY(row), 64, 20).build();
        orderShuffleNoRepeatButton = Button.builder(Component.translatable("music-delay-reducer.config.order_shuffle"), b -> setOrder("SHUFFLE_NO_REPEAT"))
                .bounds(centerX - 32, rowY(row), 64, 20).build();
        orderRandomButton = Button.builder(Component.translatable("music-delay-reducer.config.order_random"), b -> setOrder("RANDOM"))
                .bounds(centerX + 36, rowY(row), 64, 20).build();

        orderSequentialButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.order_sequential.tooltip")));
        orderShuffleNoRepeatButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.order_shuffle.tooltip")));
        orderRandomButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.order_random.tooltip")));

        this.addRenderableWidget(orderSequentialButton);
        this.addRenderableWidget(orderShuffleNoRepeatButton);
        this.addRenderableWidget(orderRandomButton);
        updateOrderButtons();
        row++;

        AbstractSliderButton minSlider = new SecondsSlider(centerX - 100, rowY(row), 200, 20,
                "music-delay-reducer.config.min_delay", MAX_SECONDS, minSeconds, value -> minSeconds = value);
        minSlider.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.min_delay.tooltip")));
        this.addRenderableWidget(minSlider);
        row++;

        AbstractSliderButton maxSlider = new SecondsSlider(centerX - 100, rowY(row), 200, 20,
                "music-delay-reducer.config.max_delay", MAX_SECONDS, maxSeconds, value -> maxSeconds = value);
        maxSlider.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.max_delay.tooltip")));
        this.addRenderableWidget(maxSlider);
        row++;

        AbstractSliderButton skipSlider = new SecondsSlider(centerX - 100, rowY(row), 200, 20,
                "music-delay-reducer.config.skip_delay", MAX_SKIP_DELAY_SECONDS, skipDelaySeconds, value -> skipDelaySeconds = value);
        skipSlider.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.skip_delay.tooltip")));
        this.addRenderableWidget(skipSlider);
        row++;

        crossfadeToggleButton = Button.builder(crossfadeButtonLabel(), b -> {
            crossfadeEnabled = !crossfadeEnabled;
            crossfadeToggleButton.setMessage(crossfadeButtonLabel());
        }).bounds(centerX - 100, rowY(row), 200, 20).build();
        crossfadeToggleButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.crossfade_toggle.tooltip")));
        this.addRenderableWidget(crossfadeToggleButton);
        row++;

        AbstractSliderButton crossfadeSlider = new TenthsSlider(centerX - 100, rowY(row), 200, 20,
                "music-delay-reducer.config.crossfade_duration", MAX_CROSSFADE_TENTHS, crossfadeDurationSeconds,
                value -> crossfadeDurationSeconds = value);
        crossfadeSlider.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.crossfade_duration.tooltip")));
        this.addRenderableWidget(crossfadeSlider);
        row++;

        startupToggleButton = Button.builder(startupButtonLabel(), b -> {
            startupFadeEnabled = !startupFadeEnabled;
            startupToggleButton.setMessage(startupButtonLabel());
        }).bounds(centerX - 100, rowY(row), 200, 20).build();
        startupToggleButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.startup_toggle.tooltip")));
        this.addRenderableWidget(startupToggleButton);
        row++;

        AbstractSliderButton startupSlider = new SecondsSlider(centerX - 100, rowY(row), 200, 20,
                "music-delay-reducer.config.startup_delay", MAX_STARTUP_DELAY_SECONDS, startupDelaySeconds,
                value -> startupDelaySeconds = Math.max(1, value));
        startupSlider.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.startup_delay.tooltip")));
        this.addRenderableWidget(startupSlider);
        row++;

        worldRestartToggleButton = Button.builder(worldRestartButtonLabel(), b -> {
            worldRestartEnabled = !worldRestartEnabled;
            worldRestartToggleButton.setMessage(worldRestartButtonLabel());
        }).bounds(centerX - 100, rowY(row), 200, 20).build();
        worldRestartToggleButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.world_restart_toggle.tooltip")));
        this.addRenderableWidget(worldRestartToggleButton);
        row++;

        Button addTrackButton = Button.builder(Component.translatable("music-delay-reducer.config.add_track"), b -> openFileChooser())
                .bounds(centerX - 100, rowY(row), 95, 20).build();
        addTrackButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.add_track.tooltip")));
        this.addRenderableWidget(addTrackButton);

        Button openFolderButton = Button.builder(Component.translatable("music-delay-reducer.config.open_folder"), b -> openTracksFolder())
                .bounds(centerX + 5, rowY(row), 95, 20).build();
        openFolderButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.config.open_folder.tooltip")));
        this.addRenderableWidget(openFolderButton);
        row++;

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.config.save"), button -> {
            config.minDelaySeconds = Math.min(minSeconds, maxSeconds);
            config.maxDelaySeconds = Math.max(minSeconds, maxSeconds);
            config.skipDelaySeconds = skipDelaySeconds;
            config.playbackMode = playbackMode;
            config.crossfadeEnabled = crossfadeEnabled;
            config.crossfadeDurationSeconds = crossfadeDurationSeconds;
            config.startupFadeEnabled = startupFadeEnabled;
            config.startupDelaySeconds = Math.max(1, startupDelaySeconds);
            config.worldRestartEnabled = worldRestartEnabled;
            config.trackOrderMode = trackOrderMode;
            config.save();
            MusicDelayReducerClient.resetPlaybackState();
            this.onClose();
        }).bounds(centerX - 100, rowY(row), 200, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        int max = maxScroll();
        if (max <= 0) return false;
        scrollAmount = Math.max(0, Math.min(max, scrollAmount - (int) (scrollY * SCROLL_STEP)));
        this.rebuildWidgets();
        return true;
    }

    private Component crossfadeButtonLabel() {
        return Component.translatable(crossfadeEnabled
                ? "music-delay-reducer.config.crossfade_on"
                : "music-delay-reducer.config.crossfade_off");
    }

    private Component startupButtonLabel() {
        return Component.translatable(startupFadeEnabled
                ? "music-delay-reducer.config.startup_on"
                : "music-delay-reducer.config.startup_off");
    }

    private Component worldRestartButtonLabel() {
        return Component.translatable(worldRestartEnabled
                ? "music-delay-reducer.config.world_restart_on"
                : "music-delay-reducer.config.world_restart_off");
    }

    private void setMode(String mode) {
        this.playbackMode = mode;
        updateModeButtons();
    }

    private void updateModeButtons() {
        vanillaButton.active = !playbackMode.equals("VANILLA");
        customButton.active = !playbackMode.equals("CUSTOM");
        bothButton.active = !playbackMode.equals("BOTH");
    }

    private void setOrder(String order) {
        this.trackOrderMode = order;
        updateOrderButtons();
    }

    private void updateOrderButtons() {
        orderSequentialButton.active = !trackOrderMode.equals("SEQUENTIAL");
        orderShuffleNoRepeatButton.active = !trackOrderMode.equals("SHUFFLE_NO_REPEAT");
        orderRandomButton.active = !trackOrderMode.equals("RANDOM");
    }

    private void openFileChooser() {
        new Thread(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(4);
                filters.put(stack.UTF8("*.wav"));
                filters.put(stack.UTF8("*.mp3"));
                filters.put(stack.UTF8("*.ogg"));
                filters.put(stack.UTF8("*.flac"));
                filters.flip();

                String result = TinyFileDialogs.tinyfd_openFileDialog(
                        "Select audio track",
                        null,
                        filters,
                        "Audio files (.wav, .mp3, .ogg, .flac)",
                        false
                );

                if (result != null) {
                    CustomTrackManager.get().addTrack(Path.of(result));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "mdr-file-chooser").start();
    }

    private void openTracksFolder() {
        Path folder = CustomTrackManager.get().getTracksFolder();
        Util.getPlatform().openFile(folder.toFile());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int scissorTop = Math.max(0, CONTENT_TOP - 5);
        int scissorBottom = this.height - BOTTOM_MARGIN + 5;
        graphics.enableScissor(0, scissorTop, this.width, scissorBottom);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
    }

    private static class SecondsSlider extends AbstractSliderButton {
        private final String translationKey;
        private final int maxValue;
        private final IntConsumer onChange;

        SecondsSlider(int x, int y, int width, int height, String translationKey, int maxValue, int initialSeconds, IntConsumer onChange) {
            super(x, y, width, height, Component.empty(), (double) initialSeconds / maxValue);
            this.translationKey = translationKey;
            this.maxValue = maxValue;
            this.onChange = onChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int seconds = (int) Math.round(this.value * maxValue);
            this.setMessage(Component.translatable(translationKey, seconds));
        }

        @Override
        protected void applyValue() {
            int seconds = (int) Math.round(this.value * maxValue);
            onChange.accept(seconds);
        }
    }

    private static class TenthsSlider extends AbstractSliderButton {
        private final String translationKey;
        private final int maxTenths;
        private final DoubleConsumer onChange;

        TenthsSlider(int x, int y, int width, int height, String translationKey, int maxTenths, double initialSeconds, DoubleConsumer onChange) {
            super(x, y, width, height, Component.empty(), Math.max(1, (int) Math.round(initialSeconds * 10.0)) / (double) maxTenths);
            this.translationKey = translationKey;
            this.maxTenths = maxTenths;
            this.onChange = onChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int tenths = Math.max(1, (int) Math.round(this.value * maxTenths));
            double seconds = tenths / 10.0;
            this.setMessage(Component.translatable(translationKey, String.format(Locale.US, "%.1f", seconds)));
        }

        @Override
        protected void applyValue() {
            int tenths = Math.max(1, (int) Math.round(this.value * maxTenths));
            onChange.accept(tenths / 10.0);
        }
    }
}