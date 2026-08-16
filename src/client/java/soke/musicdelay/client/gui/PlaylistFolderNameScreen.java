package soke.musicdelay.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import soke.musicdelay.client.Playlist;
import soke.musicdelay.client.PlaylistManager;

import java.nio.file.Path;

/**
 * Экран ввода имени для плейлиста на основе папки. Отдельный от PlaylistNameScreen,
 * потому что тот завязан на PlaylistBuilder (ручной выбор треков через "+"), а здесь
 * плейлист целиком привязывается к папке — имя нужно только чтобы завести Playlist
 * и сохранить его.
 */
public class PlaylistFolderNameScreen extends Screen {

    private final @Nullable Screen parent;
    private final Path folder;
    private EditBox nameBox;

    public PlaylistFolderNameScreen(@Nullable Screen parent, Path folder) {
        super(Component.translatable("music-delay-reducer.playlist.name_title"));
        this.parent = parent;
        this.folder = folder;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        nameBox = new EditBox(this.font, centerX - 100, centerY - 10, 200, 20, Component.translatable("music-delay-reducer.playlist.name_field"));
        nameBox.setMaxLength(48);
        this.addRenderableWidget(nameBox);
        this.setInitialFocus(nameBox);

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.playlist.save"), b -> save())
                .bounds(centerX - 100, centerY + 20, 95, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.browser.close"), b ->
                        this.minecraft.gui.setScreen(parent))
                .bounds(centerX + 5, centerY + 20, 95, 20).build());
    }

    private void save() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) return;

        Playlist playlist = new Playlist(name);
        playlist.folderPath = folder.toAbsolutePath().toString();
        PlaylistManager.add(playlist);

        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 30, 0xFFFFFFFF);
    }
}
