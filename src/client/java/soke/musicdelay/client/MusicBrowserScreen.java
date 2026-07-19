package soke.musicdelay.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class MusicBrowserScreen extends Screen {

    private static final Identifier MUSIC_NOTES_SPRITE = Identifier.parse("icon/music_notes");
    private static final int PLAY_BUTTON_SIZE = 16;
    private static final int PLUS_BUTTON_SIZE = 16;

    private final @Nullable Screen parent;
    private TrackListWidget trackList;

    public MusicBrowserScreen(@Nullable Screen parent) {
        super(Component.translatable("music-delay-reducer.browser.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        List<BrowsableTrack> allTracks = BrowsableTrack.buildFullList();

        trackList = new TrackListWidget(this.minecraft, this.width, this.height - 70, 35, 22);
        trackList.setEntries(allTracks);
        this.addRenderableWidget(trackList);

        if (PlaylistBuilder.isBuilding()) {
            this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.playlist.done"), b ->
                            this.minecraft.gui.setScreen(new PlaylistNameScreen(this)))
                    .bounds(this.width / 2 - 105, this.height - 30, 100, 20).build());

            this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.browser.close"), b -> {
                PlaylistBuilder.cancelBuilding();
                this.onClose();
            }).bounds(this.width / 2 + 5, this.height - 30, 100, 20).build());
        } else {
            this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.playlist.manager_button"), b ->
                            this.minecraft.gui.setScreen(new PlaylistManagerScreen(this)))
                    .bounds(this.width / 2 - 205, this.height - 30, 200, 20).build());

            this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.browser.close"), b -> this.onClose())
                    .bounds(this.width / 2 + 5, this.height - 30, 200, 20).build());
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        if (PlaylistBuilder.isBuilding()) {
            graphics.centeredText(this.font, Component.translatable("music-delay-reducer.playlist.building_count", PlaylistBuilder.getEntryCount()), this.width / 2, 26, 0xFF55FF55);
        }
    }

    private void onAddClicked(BrowsableTrack track) {
        if (PlaylistBuilder.isBuilding()) {
            PlaylistBuilder.addEntry(track);
            this.rebuildWidgets();
        } else {
            this.minecraft.gui.setScreen(new PlaylistChooserScreen(this, track));
        }
    }

    private void onPlayClicked(BrowsableTrack track) {
        MusicDelayReducerClient.playFromBrowser(track);
    }

    private class TrackListWidget extends ObjectSelectionList<TrackListWidget.TrackEntry> {

        TrackListWidget(net.minecraft.client.Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void setEntries(List<BrowsableTrack> tracks) {
            this.clearEntries();
            for (BrowsableTrack track : tracks) {
                this.addEntry(new TrackEntry(track));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.min(420, this.width - 20);
        }

        class TrackEntry extends ObjectSelectionList.Entry<TrackEntry> {
            final BrowsableTrack track;
            boolean selected = false;

            TrackEntry(BrowsableTrack track) {
                this.track = track;
            }

            @Override
            public Component getNarration() {
                return track.displayName;
            }

            private int playButtonX() {
                return getContentRight() - PLAY_BUTTON_SIZE - PLUS_BUTTON_SIZE - 8;
            }

            private int plusButtonX() {
                return getContentRight() - PLUS_BUTTON_SIZE;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
                if (track.kind == BrowsableTrack.Kind.HEADER) {
                    graphics.centeredText(font, track.displayName, getContentXMiddle(), getContentY() + 5, 0xFFFFFF55);
                    return;
                }

                if (hovered || selected) {
                    graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                            selected ? 0x80336633 : 0x40FFFFFF);
                }

                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MUSIC_NOTES_SPRITE, getContentX(), getContentY() + 3, 16, 16, -1);

                int textX = getContentX() + 20;
                int maxTextWidth = playButtonX() - textX - 6;
                Component displayText = font.width(track.displayName) > maxTextWidth
                        ? Component.literal(font.plainSubstrByWidth(track.displayName.getString(), maxTextWidth) + "...")
                        : track.displayName;
                graphics.text(font, displayText, textX, getContentY() + 6, 0xFFDDDDDD);

                boolean playHovered = isOverButton(mouseX, mouseY, playButtonX(), PLAY_BUTTON_SIZE);
                graphics.text(font, "\u25B6", playButtonX() + 3, getContentY() + 6, playHovered ? 0xFFFFFFFF : 0xFF55FF55);

                boolean plusHovered = isOverButton(mouseX, mouseY, plusButtonX(), PLUS_BUTTON_SIZE);
                graphics.text(font, "+", plusButtonX() + 5, getContentY() + 6, plusHovered ? 0xFFFFFFFF : 0xFF55AAFF);
            }

            private boolean isOverButton(int mouseX, int mouseY, int buttonX, int size) {
                return mouseX >= buttonX && mouseX < buttonX + size && mouseY >= getY() && mouseY < getY() + getHeight();
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                if (track.kind == BrowsableTrack.Kind.HEADER) return false;

                int mx = (int) event.x();
                int my = (int) event.y();

                if (isOverButton(mx, my, playButtonX(), PLAY_BUTTON_SIZE)) {
                    onPlayClicked(track);
                    return true;
                }
                if (isOverButton(mx, my, plusButtonX(), PLUS_BUTTON_SIZE)) {
                    onAddClicked(track);
                    return true;
                }

                selected = !selected;
                return true;
            }
        }
    }
}