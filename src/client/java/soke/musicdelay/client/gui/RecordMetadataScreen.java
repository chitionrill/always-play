package soke.musicdelay.client.gui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import soke.musicdelay.client.BrowsableTrack;
import soke.musicdelay.client.Playlist;
import soke.musicdelay.client.gui.CustomTrackToast;
import soke.musicdelay.network.RecordCustomDataPayload;

// Второй экран записи кастомной пластинки: название композиции и композитор. По "Готово"
// отправляет пакет на сервер (см. RecordCustomDataPayload) — сам предмет здесь не трогаем,
// его меняет только сервер, чтобы не рассинхронизироваться с реальным инвентарём (см. пояснение
// в MusicDelayReducer.java).
public class RecordMetadataScreen extends Screen {

    private final Screen parent;
    private final BrowsableTrack track;
    private EditBox titleBox;
    private EditBox composerBox;

    public RecordMetadataScreen(Screen parent, BrowsableTrack track) {
        super(Component.translatable("music-delay-reducer.record.metadata_title"));
        this.parent = parent;
        this.track = track;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        titleBox = new EditBox(this.font, centerX - 100, centerY - 30, 200, 20, Component.translatable("music-delay-reducer.record.title_field"));
        titleBox.setMaxLength(64);
        titleBox.setHint(Component.translatable("music-delay-reducer.record.title_field"));
        titleBox.setValue(track.displayName.getString());
        this.addRenderableWidget(titleBox);
        this.setInitialFocus(titleBox);

        composerBox = new EditBox(this.font, centerX - 100, centerY - 5, 200, 20, Component.translatable("music-delay-reducer.record.composer_field"));
        composerBox.setMaxLength(64);
        composerBox.setHint(Component.translatable("music-delay-reducer.record.composer_field"));
        this.addRenderableWidget(composerBox);

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.record.done"), b -> onDoneClicked())
                .bounds(centerX - 100, centerY + 25, 95, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.record.back"), b ->
                        this.minecraft.gui.setScreen(parent))
                .bounds(centerX + 5, centerY + 25, 95, 20).build());
    }

    private void onDoneClicked() {
        String title = titleBox.getValue().trim();
        String composer = composerBox.getValue().trim();
        if (title.isEmpty()) title = track.displayName.getString();

        soke.musicdelay.client.jukebox.SharedJukeboxClient.record(track, title, composer);
        this.minecraft.gui.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 55, 0xFFFFFFFF);
    }
}