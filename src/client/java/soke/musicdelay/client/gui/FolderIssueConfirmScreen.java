package soke.musicdelay.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import soke.musicdelay.client.musiclibrary.FolderIssueMessages;
import soke.musicdelay.client.musiclibrary.FolderValidation;

/**
 * Модальный экран, который появляется, когда выбранная игроком папка оказалась недоступна
 * (удалена, пуста, нет прав на чтение и т.п.). В отличие от всплывающего уведомления, этот
 * экран не закрывается сам — игрок обязан нажать кнопку, тем самым подтвердив, что заметил
 * проблему.
 */
public final class FolderIssueConfirmScreen extends Screen {

    private final @Nullable Screen parent;
    private final FolderValidation.Result issue;
    private final Runnable onAcknowledge;

    public FolderIssueConfirmScreen(@Nullable Screen parent, FolderValidation.Result issue, Runnable onAcknowledge) {
        super(Component.translatable("music-delay-reducer.folder_issue.title"));
        this.parent = parent;
        this.issue = issue;
        this.onAcknowledge = onAcknowledge;
    }

    @Override
    protected void init() {
        int buttonWidth = 150;
        int buttonX = (this.width - buttonWidth) / 2;
        int buttonY = this.height / 2 + 20;

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.folder_issue.acknowledge"), b -> {
            onAcknowledge.run();
            this.onClose();
        }).bounds(buttonX, buttonY, buttonWidth, 20).build());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);

        String message = FolderIssueMessages.describe(issue);
        graphics.centeredText(this.font, Component.literal(message), this.width / 2, this.height / 2 - 20, 0xFFAAAAAA);

        graphics.centeredText(this.font, Component.translatable("music-delay-reducer.folder_issue.fallback_notice"),
                this.width / 2, this.height / 2, 0xFFAAAAAA);
    }
}