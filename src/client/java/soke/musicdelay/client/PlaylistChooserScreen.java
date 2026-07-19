package soke.musicdelay.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class PlaylistChooserScreen extends Screen {

    private final @Nullable Screen parent;
    private final BrowsableTrack track;

    public PlaylistChooserScreen(@Nullable Screen parent, BrowsableTrack track) {
        super(Component.translatable("music-delay-reducer.playlist.choose_title"));
        this.parent = parent;
        this.track = track;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.playlist.create_new"), b -> {
            PlaylistBuilder.startBuilding();
            PlaylistBuilder.addEntry(track);
            this.minecraft.gui.setScreen(parent);
        }).bounds(centerX - 100, 35, 200, 20).build());

        List<Playlist> playlists = PlaylistManager.getAll();
        PlaylistListWidget list = new PlaylistListWidget(this.minecraft, this.width, this.height - 100, 65, 22);
        list.setPlaylists(playlists);
        this.addRenderableWidget(list);

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.browser.close"), b -> this.onClose())
                .bounds(centerX - 100, this.height - 30, 200, 20).build());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
    }

    private class PlaylistListWidget extends ObjectSelectionList<PlaylistListWidget.PlaylistEntry> {

        PlaylistListWidget(net.minecraft.client.Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void setPlaylists(List<Playlist> playlists) {
            this.clearEntries();
            for (Playlist playlist : playlists) {
                this.addEntry(new PlaylistEntry(playlist));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.min(300, this.width - 20);
        }

        class PlaylistEntry extends ObjectSelectionList.Entry<PlaylistEntry> {
            final Playlist playlist;

            PlaylistEntry(Playlist playlist) {
                this.playlist = playlist;
            }

            @Override
            public Component getNarration() {
                return Component.literal(playlist.name);
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
                if (hovered) {
                    graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x40FFFFFF);
                }
                graphics.text(font, Component.literal(playlist.name), getContentX() + 4, getContentY() + 6, 0xFFDDDDDD);
                graphics.text(font, "+", getContentRight() - 12, getContentY() + 6, 0xFF55AAFF);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                playlist.entries.add(track.toPlaylistEntry());
                PlaylistManager.persist();
                PlaylistChooserScreen.this.onClose();
                return true;
            }
        }
    }
}