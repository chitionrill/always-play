package soke.musicdelay.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

public class PlaylistDetailScreen extends Screen {

    private final @Nullable Screen parent;
    private final Playlist playlist;
    private EntryListWidget list;

    public PlaylistDetailScreen(@Nullable Screen parent, Playlist playlist) {
        super(Component.literal(playlist.name));
        this.parent = parent;
        this.playlist = playlist;
    }

    @Override
    protected void init() {
        list = new EntryListWidget(this.minecraft, this.width, this.height - 70, 35, 20);
        refreshList();
        this.addRenderableWidget(list);

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.browser.close"), b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    private void refreshList() {
        list.setEntries(playlist.entries);
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

    private class EntryListWidget extends ObjectSelectionList<EntryListWidget.TrackRow> {

        EntryListWidget(net.minecraft.client.Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void setEntries(java.util.List<Playlist.PlaylistEntry> entries) {
            this.clearEntries();
            for (Playlist.PlaylistEntry entry : entries) {
                this.addEntry(new TrackRow(entry));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.min(400, this.width - 20);
        }

        class TrackRow extends ObjectSelectionList.Entry<TrackRow> {
            final Playlist.PlaylistEntry entry;

            TrackRow(Playlist.PlaylistEntry entry) {
                this.entry = entry;
            }

            private Component displayName() {
                if ("CUSTOM".equals(entry.type)) {
                    String name = Path.of(entry.value).getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) name = name.substring(0, dot);
                    return Component.literal(name);
                }
                return Component.translatable(entry.value.replace("minecraft:", "").replace("/", ".").replace(":", "."));
            }

            @Override
            public Component getNarration() {
                return displayName();
            }

            private int removeButtonX() {
                return getContentRight() - 16;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
                if (hovered) {
                    graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x40FFFFFF);
                }
                graphics.text(font, displayName(), getContentX() + 4, getContentY() + 6, 0xFFDDDDDD);

                boolean removeHovered = mouseX >= removeButtonX() && mouseX < removeButtonX() + 16 && mouseY >= getY() && mouseY < getY() + getHeight();
                graphics.text(font, "X", removeButtonX() + 4, getContentY() + 6, removeHovered ? 0xFFFFFFFF : 0xFFFF5555);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                int mx = (int) event.x();
                if (mx >= removeButtonX()) {
                    playlist.entries.remove(entry);
                    PlaylistManager.persist();
                    refreshList();
                    return true;
                }
                return true;
            }
        }
    }
}