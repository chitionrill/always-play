package soke.musicdelay.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

public class ConfirmActionScreen extends Screen {

    private final @Nullable Screen parent;
    private final Component message;
    private final Runnable onConfirm;

    public ConfirmActionScreen(@Nullable Screen parent, Component title, Component message, Runnable onConfirm) {
        super(title);
        this.parent = parent;
        this.message = message;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.confirm.yes"), b -> {
            onConfirm.run();
            this.minecraft.gui.setScreen(parent);
        }).bounds(centerX - 105, centerY + 20, 100, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.confirm.no"), b ->
                        this.minecraft.gui.setScreen(parent))
                .bounds(centerX + 5, centerY + 20, 100, 20).build());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
        graphics.centeredText(this.font, this.message, this.width / 2, this.height / 2 - 10, 0xFFDDDDDD);
    }
}