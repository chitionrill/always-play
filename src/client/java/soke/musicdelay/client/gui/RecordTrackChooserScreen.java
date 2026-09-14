package soke.musicdelay.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import soke.musicdelay.client.BrowsableTrack;
import soke.musicdelay.client.CustomTrackManager;
import soke.musicdelay.client.MusicDelayReducerClient;
import soke.musicdelay.client.musiclibrary.BrowserTrackFilter;
import soke.musicdelay.client.musiclibrary.FolderTrackLibrary;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Первый экран записи кастомной пластинки: список всех треков, доступных игроку (тот же список,
// что в MusicBrowserScreen — переиспользуем TrackListWidget/BrowsableTrack как есть), но выбор
// одиночный (не мульти-select для плейлиста), и вместо "добавить в плейлист" — переход к экрану
// метаданных (RecordMetadataScreen).
public class RecordTrackChooserScreen extends Screen implements TrackRowCallbacks {

    private final @Nullable Screen parent;
    private TrackListWidget trackList;
    private EditBox searchBox;
    private Button nextButton;
    private List<BrowsableTrack> allTracks;

    private @Nullable BrowsableTrack selectedTrack;
    private final Set<String> collapsedHeaders = new HashSet<>();

    private double savedScrollAmount = 0;
    private String savedQuery = "";

    public RecordTrackChooserScreen(@Nullable Screen parent) {
        super(Component.translatable("music-delay-reducer.record.choose_track_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (trackList != null) {
            savedScrollAmount = trackList.getScrollAmountPublic();
        }
        if (searchBox != null) {
            savedQuery = searchBox.getValue();
        }

        allTracks = BrowsableTrack.buildFullList();
        int centerX = this.width / 2;

        searchBox = new EditBox(this.font, centerX - 100, 35, 130, 20, Component.translatable("music-delay-reducer.browser.search"));
        searchBox.setHint(Component.translatable("music-delay-reducer.browser.search"));
        searchBox.setValue(savedQuery);
        searchBox.setResponder(this::applyFilter);
        this.addRenderableWidget(searchBox);

        Button refreshButton = Button.builder(Component.translatable("music-delay-reducer.browser.refresh"), b -> refreshTracks())
                .bounds(centerX + 35, 35, 65, 20).build();
        this.addRenderableWidget(refreshButton);

        trackList = new TrackListWidget(this.minecraft, this.width, this.height - 90, 60, 22, this);
        this.addRenderableWidget(trackList);
        applyFilter(savedQuery);
        trackList.setScrollAmount(savedScrollAmount);

        FolderTrackLibrary.get().rescan(() ->
                Minecraft.getInstance().execute(() -> applyFilter(searchBox.getValue())));

        nextButton = Button.builder(Component.translatable("music-delay-reducer.record.next"), b -> onNextClicked())
                .bounds(centerX - 100, this.height - 30, 95, 20).build();
        this.addRenderableWidget(nextButton);

        this.addRenderableWidget(Button.builder(Component.translatable("music-delay-reducer.browser.close"), b -> this.onClose())
                .bounds(centerX + 5, this.height - 30, 95, 20).build());
    }

    private void applyFilter(String rawQuery) {
        List<BrowsableTrack> filtered = BrowserTrackFilter.filter(
                allTracks, FolderTrackLibrary.get().library().getTopLevelGroups(), rawQuery, collapsedHeaders);
        trackList.setEntries(filtered);
    }

    private void refreshTracks() {
        CustomTrackManager.get().refresh();
        FolderTrackLibrary.get().rescan(() ->
                Minecraft.getInstance().execute(() -> applyFilter(searchBox.getValue())));
    }

    private void onNextClicked() {
        if (selectedTrack == null) return;
        this.minecraft.gui.setScreen(new RecordMetadataScreen(this, selectedTrack));
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        nextButton.active = selectedTrack != null;
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
    }

    // --- TrackRowCallbacks ---

    @Override
    public boolean isSelected(BrowsableTrack track) {
        return track.equals(selectedTrack);
    }

    @Override
    public void toggleSelected(BrowsableTrack track) {
        selectedTrack = track.equals(selectedTrack) ? null : track;
    }

    @Override
    public void onPlay(BrowsableTrack track) {
        MusicDelayReducerClient.playFromBrowser(track);
    }

    @Override
    public void onAdd(BrowsableTrack track) {
        // Короткий путь: сразу выбрать этот трек и перейти дальше, без отдельного клика "Далее".
        selectedTrack = track;
        onNextClicked();
    }

    @Override
    public void onToggleHeader(BrowsableTrack header) {
        double scroll = trackList.getScrollAmountPublic();
        String key = BrowserTrackFilter.collapseKey(header);
        if (!collapsedHeaders.remove(key)) {
            collapsedHeaders.add(key);
        }
        applyFilter(searchBox.getValue());
        trackList.setScrollAmount(scroll);
    }

    @Override
    public boolean isHeaderCollapsed(BrowsableTrack header) {
        return collapsedHeaders.contains(BrowserTrackFilter.collapseKey(header));
    }
}
