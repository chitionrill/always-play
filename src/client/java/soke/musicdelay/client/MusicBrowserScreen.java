package soke.musicdelay.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MusicBrowserScreen extends Screen {

    private static final Identifier MUSIC_NOTES_SPRITE = Identifier.parse("icon/music_notes");
    private static final int PLAY_BUTTON_SIZE = 16;
    private static final int PLUS_BUTTON_SIZE = 16;

    private final @Nullable Screen parent;
    private TrackListWidget trackList;
    private EditBox searchBox;
    private Button addSelectedButton;
    private List<BrowsableTrack> allTracks;

    private final Set<BrowsableTrack> selectedTracks = new LinkedHashSet<>();

    private double savedScrollAmount = 0;
    private String savedQuery = "";

    public MusicBrowserScreen(@Nullable Screen parent) {
        super(Component.translatable("music-delay-reducer.browser.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Запоминаем позицию прокрутки и текст поиска перед пересборкой экрана,
        // чтобы список не "прыгал" на верх после добавления трека в плейлист
        if (trackList != null) {
            savedScrollAmount = trackList.getScrollAmountPublic();
        }
        if (searchBox != null) {
            savedQuery = searchBox.getValue();
        }

        allTracks = BrowsableTrack.buildFullList();
        int centerX = this.width / 2;

        searchBox = new EditBox(this.font, centerX - 100, 35, 200, 20, Component.translatable("music-delay-reducer.browser.search"));
        searchBox.setHint(Component.translatable("music-delay-reducer.browser.search"));
        searchBox.setValue(savedQuery);
        searchBox.setResponder(this::applyFilter);
        this.addRenderableWidget(searchBox);

        addSelectedButton = Button.builder(Component.literal(""), b -> onAddSelectedClicked())
                .bounds(centerX - 100, 60, 200, 20).build();
        this.addRenderableWidget(addSelectedButton);

        trackList = new TrackListWidget(this.minecraft, this.width, this.height - 120, 85, 22);
        this.addRenderableWidget(trackList);
        applyFilter(savedQuery);
        trackList.setScrollAmount(savedScrollAmount);

        if (searchBox.getValue().isEmpty()) {
            this.setInitialFocus(searchBox);
        }

        if (PlaylistBuilder.isBuilding()) {
            this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.playlist.done"), b ->
                            this.minecraft.gui.setScreen(new PlaylistNameScreen(this)))
                    .bounds(centerX - 105, this.height - 30, 100, 20).build());

            this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.browser.close"), b -> {
                PlaylistBuilder.cancelBuilding();
                this.onClose();
            }).bounds(centerX + 5, this.height - 30, 100, 20).build());
        } else {
            this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.playlist.manager_button"), b ->
                            this.minecraft.gui.setScreen(new PlaylistManagerScreen(this)))
                    .bounds(centerX - 205, this.height - 30, 200, 20).build());

            this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.browser.close"), b -> this.onClose())
                    .bounds(centerX + 5, this.height - 30, 200, 20).build());
        }
    }

    private void applyFilter(String rawQuery) {
        String query = rawQuery.trim().toLowerCase(Locale.ROOT);
        List<BrowsableTrack> filtered = new ArrayList<>();
        List<BrowsableTrack> pendingSection = new ArrayList<>();
        BrowsableTrack pendingHeader = null;

        for (BrowsableTrack track : allTracks) {
            if (track.kind == BrowsableTrack.Kind.HEADER) {
                if (pendingHeader != null && !pendingSection.isEmpty()) {
                    filtered.add(pendingHeader);
                    filtered.addAll(pendingSection);
                }
                pendingHeader = track;
                pendingSection = new ArrayList<>();
            } else {
                boolean matches = query.isEmpty() || track.displayName.getString().toLowerCase(Locale.ROOT).contains(query);
                if (matches) {
                    if (pendingHeader == null) {
                        filtered.add(track);
                    } else {
                        pendingSection.add(track);
                    }
                }
            }
        }
        if (pendingHeader != null && !pendingSection.isEmpty()) {
            filtered.add(pendingHeader);
            filtered.addAll(pendingSection);
        }

        trackList.setEntries(filtered);
    }

    private void onAddClicked(BrowsableTrack track) {
        if (PlaylistBuilder.isBuilding()) {
            PlaylistBuilder.addEntry(track);
            this.rebuildWidgets();
        } else {
            this.minecraft.gui.setScreen(new PlaylistChooserScreen(this, List.of(track)));
        }
    }

    // Добавляет разом все выделенные (зелёные) треки — либо в текущий собираемый плейлист,
    // либо открывает выбор плейлиста для всей группы сразу
    private void onAddSelectedClicked() {
        if (selectedTracks.isEmpty()) return;
        List<BrowsableTrack> tracks = new ArrayList<>(selectedTracks);
        selectedTracks.clear();

        if (PlaylistBuilder.isBuilding()) {
            for (BrowsableTrack track : tracks) {
                PlaylistBuilder.addEntry(track);
            }
            this.rebuildWidgets();
        } else {
            this.minecraft.gui.setScreen(new PlaylistChooserScreen(this, tracks));
        }
    }

    private void onPlayClicked(BrowsableTrack track) {
        MusicDelayReducerClient.playFromBrowser(track);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        addSelectedButton.visible = !selectedTracks.isEmpty();
        if (addSelectedButton.visible) {
            addSelectedButton.setMessage(Component.translatable("music-delay-reducer.browser.add_selected", selectedTracks.size()));
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        if (PlaylistBuilder.isBuilding()) {
            graphics.centeredText(this.font, Component.translatable("music-delay-reducer.playlist.building_count", PlaylistBuilder.getEntryCount()), this.width / 2, 26, 0xFF55FF55);
        }
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

        double getScrollAmountPublic() {
            return this.scrollAmount();
        }

        @Override
        public int getRowWidth() {
            return Math.min(420, this.width - 20);
        }

        class TrackEntry extends ObjectSelectionList.Entry<TrackEntry> {
            final BrowsableTrack track;

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

                boolean isSelected = selectedTracks.contains(track);
                if (hovered || isSelected) {
                    graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                            isSelected ? 0x80336633 : 0x40FFFFFF);
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

                if (selectedTracks.contains(track)) {
                    selectedTracks.remove(track);
                } else {
                    selectedTracks.add(track);
                }
                return true;
            }
        }
    }
}