package soke.musicdelay.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class PlaylistManagerScreen extends Screen {

    private final @Nullable Screen parent;
    private PlaylistListWidget list;

    public PlaylistManagerScreen(@Nullable Screen parent) {
        super(Component.translatable("music-delay-reducer.playlist.manager_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        list = new PlaylistListWidget(this.minecraft, this.width, this.height - 70, 35, 24);
        refreshList();
        this.addRenderableWidget(list);

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.playlist.stop_active"), b -> {
            PlaylistManager.setActivePlaylist(null);
            ModConfig.get().activePlaylistId = null;
            ModConfig.get().save();
            MusicDelayReducerClient.resetPlaybackState();
            refreshList();
        }).bounds(this.width / 2 - 205, this.height - 30, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.browser.close"), b -> this.onClose())
                .bounds(this.width / 2 + 5, this.height - 30, 200, 20).build());
    }

    private void refreshList() {
        list.setPlaylists(PlaylistManager.getAll());
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
            return Math.min(400, this.width - 20);
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

            private boolean isActive() {
                return playlist.id.equals(PlaylistManager.getActivePlaylistId());
            }

            private int deleteButtonX() {
                return getContentRight() - 16;
            }

            private int viewButtonX() {
                return deleteButtonX() - 40;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
                if (hovered || isActive()) {
                    graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                            isActive() ? 0x8033AA33 : 0x40FFFFFF);
                }

                String label = playlist.name + " (" + playlist.entries.size() + ")";
                graphics.text(font, Component.literal(label), getContentX() + 4, getContentY() + 6, 0xFFDDDDDD);

                boolean viewHovered = isOverButton(mouseX, mouseY, viewButtonX(), 32);
                graphics.text(font, Component.translatable("music-delay-reducer.playlist.view"), viewButtonX(), getContentY() + 6, viewHovered ? 0xFFFFFFFF : 0xFF55AAFF);

                boolean deleteHovered = isOverButton(mouseX, mouseY, deleteButtonX(), 16);
                graphics.text(font, "X", deleteButtonX() + 4, getContentY() + 6, deleteHovered ? 0xFFFFFFFF : 0xFFFF5555);
            }

            private boolean isOverButton(int mouseX, int mouseY, int buttonX, int width) {
                return mouseX >= buttonX && mouseX < buttonX + width && mouseY >= getY() && mouseY < getY() + getHeight();
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                int mx = (int) event.x();
                int my = (int) event.y();

                if (isOverButton(mx, my, deleteButtonX(), 16)) {
                    PlaylistManager.remove(playlist);
                    if (isActive()) {
                        ModConfig.get().activePlaylistId = null;
                        ModConfig.get().save();
                        MusicDelayReducerClient.resetPlaybackState();
                    }
                    refreshList();
                    return true;
                }

                if (isOverButton(mx, my, viewButtonX(), 32)) {
                    PlaylistManagerScreen.this.minecraft.gui.setScreen(new PlaylistDetailScreen(PlaylistManagerScreen.this, playlist));
                    return true;
                }

                PlaylistManager.setActivePlaylist(playlist.id);
                ModConfig.get().activePlaylistId = playlist.id;
                ModConfig.get().save();
                MusicDelayReducerClient.resetPlaybackState();
                refreshList();
                return true;
            }
        }
    }
}