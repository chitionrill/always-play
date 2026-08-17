package soke.musicdelay.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import soke.musicdelay.client.BrowsableTrack;
import soke.musicdelay.client.CustomTrackManager;
import soke.musicdelay.client.MusicDelayReducerClient;
import soke.musicdelay.client.PlaylistBuilder;
import soke.musicdelay.client.musiclibrary.BrowserTrackFilter;
import soke.musicdelay.client.musiclibrary.FolderTrackLibrary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MusicBrowserScreen extends Screen implements TrackRowCallbacks {

    private final @Nullable Screen parent;
    private TrackListWidget trackList;
    private EditBox searchBox;
    private Button addSelectedButton;
    private List<BrowsableTrack> allTracks;

    private final Set<BrowsableTrack> selectedTracks = new LinkedHashSet<>();

    // Ключ — groupId для заголовков из дерева папок, текст заголовка для старых
    // разделов (Ambient/Discs/Custom), см. BrowserTrackFilter.collapseKey().
    private static final Set<String> collapsedHeaders = new java.util.HashSet<>();

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

        searchBox = new EditBox(this.font, centerX - 100, 35, 130, 20, Component.translatable("music-delay-reducer.browser.search"));
        searchBox.setHint(Component.translatable("music-delay-reducer.browser.search"));
        searchBox.setValue(savedQuery);
        searchBox.setResponder(this::applyFilter);
        this.addRenderableWidget(searchBox);

        Button refreshButton = Button.builder(Component.translatable("music-delay-reducer.browser.refresh"), b -> refreshTracks())
                .bounds(centerX + 35, 35, 65, 20).build();
        refreshButton.setTooltip(Tooltip.create(Component.translatable("music-delay-reducer.browser.refresh.tooltip")));
        this.addRenderableWidget(refreshButton);

        addSelectedButton = Button.builder(Component.literal(""), b -> onAddSelectedClicked())
                .bounds(centerX - 100, 60, 200, 20).build();
        this.addRenderableWidget(addSelectedButton);

        trackList = new TrackListWidget(this.minecraft, this.width, this.height - 120, 85, 22, this);
        this.addRenderableWidget(trackList);
        applyFilter(savedQuery);
        trackList.setScrollAmount(savedScrollAmount);

        // Дерево папок сканируется только по явным триггерам (добавлен трек, выбрана папка,
        // нажата "Обновить") — при обычном открытии браузера ничего из этого могло ещё не
        // происходить, и список источников остаётся пустым. Поэтому сканируем и здесь тоже:
        // список сразу отрисовался (пусть даже без папок на первый кадр), а через мгновение,
        // когда фоновое сканирование закончится, применяется свежий результат.
        FolderTrackLibrary.get().rescan(() ->
                Minecraft.getInstance().execute(() -> applyFilter(searchBox.getValue())));

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
        List<BrowsableTrack> filtered = BrowserTrackFilter.filter(
                allTracks, FolderTrackLibrary.get().library().getTopLevelGroups(), rawQuery, collapsedHeaders);
        trackList.setEntries(filtered);
    }

    // Пересканирование по кнопке рядом с поиском. Само сканирование идёт в фоне;
    // как только оно закончится, список нужно перестроить на клиентском потоке —
    // это единственное место, где браузер уже открыт в момент завершения скана.
    private void refreshTracks() {
        CustomTrackManager.get().refresh();
        FolderTrackLibrary.get().rescan(() ->
                Minecraft.getInstance().execute(() -> applyFilter(searchBox.getValue())));
    }

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

    // --- TrackRowCallbacks: действия, которые может вызвать строка списка ---

    @Override
    public boolean isSelected(BrowsableTrack track) {
        return selectedTracks.contains(track);
    }

    @Override
    public void toggleSelected(BrowsableTrack track) {
        if (!selectedTracks.remove(track)) {
            selectedTracks.add(track);
        }
    }

    @Override
    public void onPlay(BrowsableTrack track) {
        MusicDelayReducerClient.playFromBrowser(track);
    }

    @Override
    public void onAdd(BrowsableTrack track) {
        if (PlaylistBuilder.isBuilding()) {
            PlaylistBuilder.addEntry(track);
            this.rebuildWidgets();
        } else {
            this.minecraft.gui.setScreen(new PlaylistChooserScreen(this, List.of(track)));
        }
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