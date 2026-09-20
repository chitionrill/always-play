package soke.musicdelay.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import soke.musicdelay.client.cache.AudioCacheManager;
import soke.musicdelay.client.musiclibrary.FolderTrackLibrary;
import java.util.*;
import static soke.musicdelay.client.cache.AudioCacheStore.*;

/** Paged lists keep all controls reachable even at the smallest GUI resolution. */
public final class AudioCacheScreen extends Screen {
    private enum Mode { SOURCES, TRACKS, CATEGORY, EXIT }
    private final Screen parent;
    private final Mode mode;
    private Source source;
    private List<Source> sources = List.of();
    private List<Track> tracks = List.of();
    private Map<String, Long> sizes = Map.of();
    private final Set<String> selected = new LinkedHashSet<>();
    private int page;
    private boolean busy, remember;
    private Component status = Component.empty();
    public AudioCacheScreen(Screen parent) { this(parent, Mode.SOURCES, null); }
    private AudioCacheScreen(Screen parent, Mode mode, Source source) {
        super(tr("title")); this.parent = parent; this.mode = mode; this.source = source;
    }
    public static AudioCacheScreen category(Screen parent, Source s) { return new AudioCacheScreen(parent, Mode.CATEGORY, s); }
    public static AudioCacheScreen departure(Screen parent, Source s) { return new AudioCacheScreen(parent, Mode.EXIT, s); }
    private static Component tr(String key, Object... args) { return Component.translatable("music-delay-reducer.cache." + key, args); }
    private String sourceName(Source s) { return s.kind() == Kind.LEGACY ? tr("legacy").getString() : s.name(); }
    private String size(long bytes) { return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0); }
    @Override protected void init() {
        if (mode == Mode.SOURCES || mode == Mode.TRACKS) reload(); else rebuildWidgets();
    }
    private void reload() {
        busy = true; status = tr("loading"); rebuildWidgets();
        AudioCacheManager.run(() -> {
            var store = AudioCacheManager.store();
            if (mode == Mode.SOURCES) {
                List<Source> list = store.sources(); Map<String, Long> totals = new HashMap<>();
                for (Source s : list) totals.put(s.id(), store.tracks(s.id()).stream().mapToLong(Track::bytes).sum());
                return new Snapshot(list, List.of(), totals);
            }
            return new Snapshot(List.of(), store.tracks(source.id()), Map.of());
        }, data -> {
            sources = data.sources; tracks = data.tracks; sizes = data.sizes;
            busy = false; status = tr("local_only"); rebuildWidgets();
        }, this::failed);
    }
    private record Snapshot(List<Source> sources, List<Track> tracks, Map<String, Long> sizes) {}
    private void failed(String message) { busy = false; status = tr("failed"); rebuildWidgets(); }
    private void button(Component text, int x, int y, int width, boolean enabled, Runnable action) {
        Button b = Button.builder(text, ignored -> action.run()).bounds(x, y, width, 20).build();
        b.active = enabled && !busy; addRenderableWidget(b);
    }
    @Override protected void rebuildWidgets() {
        clearWidgets(); int x = width / 2; int w = Math.min(400, width - 20); int left = x-w/2;
        if (mode == Mode.CATEGORY) {
            button(tr("kind_friends"), x-120, 75, 240, true, () -> choose(Kind.FRIENDS));
            button(tr("kind_public"), x-120, 100, 240, true, () -> choose(Kind.PUBLIC));
        } else if (mode == Mode.EXIT) {
            button(tr(remember ? "remember_on" : "remember_off"), x-120, 75, 240, true, () -> { remember = !remember; rebuildWidgets(); });
            button(tr("keep"), x-120, 100, 115, true, () -> depart(false));
            button(tr("delete"), x+5, 100, 115, true, () -> depart(true));
        } else {
            int count = mode == Mode.SOURCES ? sources.size() : tracks.size();
            int perPage = Math.max(1, (height - 150) / 24);
            int pages = Math.max(1, (count+perPage-1)/perPage); page = Math.min(page, pages-1);
            for (int n=page*perPage; n<Math.min(count, (page+1)*perPage); n++) {
                int y = 47 + (n-page*perPage)*24;
                String id = mode == Mode.SOURCES ? sources.get(n).id() : tracks.get(n).hash();
                String label;
                if (mode == Mode.SOURCES) {
                    Source s = sources.get(n);
                    label = sourceName(s) + " · " + tr("kind_" + s.kind().name().toLowerCase(Locale.ROOT)).getString() + " · " + size(sizes.getOrDefault(id, 0L));
                    button(Component.literal("›"), left+w-24, y, 24, true,
                            () -> minecraft.gui.setScreen(new AudioCacheScreen(this, Mode.TRACKS, s)));
                } else { Track t = tracks.get(n); label = t.title() + " · " + size(t.bytes()); }
                int textWidth = w - (mode == Mode.SOURCES ? 60 : 36);
                button(Component.literal((selected.contains(id) ? "☑ " : "☐ ") + font.plainSubstrByWidth(label, textWidth)), left, y,
                        w - (mode == Mode.SOURCES ? 28 : 0), true, () -> { if (!selected.remove(id)) selected.add(id); rebuildWidgets(); });
            }
            int controls = height-97;
            button(Component.literal("‹"), left, controls, 30, page>0, () -> { page--; rebuildWidgets(); });
            button(Component.literal((page+1)+" / "+pages+" ›"), left+35, controls, 65, page+1<pages, () -> { page++; rebuildWidgets(); });
            button(tr("select_all"), left+105, controls, Math.max(60,w-105), count>0, () -> {
                if (selected.size() == count) selected.clear();
                else if (mode == Mode.SOURCES) sources.forEach(s -> selected.add(s.id()));
                else tracks.forEach(t -> selected.add(t.hash())); rebuildWidgets();
            });
            boolean protectedSelection = mode == Mode.TRACKS ? AudioCacheManager.protectedSource(source.id())
                    : selected.stream().anyMatch(AudioCacheManager::protectedSource);
            button(tr("delete_selected"), left, height-72, w/2-3, !selected.isEmpty() && !protectedSelection, this::confirmDelete);
            if (mode == Mode.TRACKS) {
                button(tr("import"), left+w/2+3, height-72, w/2-3,
                        (source.kind() == Kind.FRIENDS || source.kind() == Kind.WORLD) && !selected.isEmpty(), this::importTracks);
                if (source.kind() == Kind.FRIENDS || source.kind() == Kind.PUBLIC || source.kind() == Kind.UNKNOWN) {
                    button(tr("change_kind"), left, height-47, w/2-3, true,
                            () -> minecraft.gui.setScreen(category(parent, source)));
                    button(tr("policy_" + source.policy().name().toLowerCase(Locale.ROOT)), left+w/2+3, height-47, w/2-3,
                            source.kind() == Kind.PUBLIC, this::cyclePolicy);
                }
                if (protectedSelection) status = tr("active");
            }
        }
        button(tr("back"), x-70, height-22, 140, true, this::onClose);
    }
    private void choose(Kind kind) {
        busy = true; rebuildWidgets();
        AudioCacheManager.configure(source, kind, source.policy(), updated -> { source = updated; busy = false; onClose(); }, this::failed);
    }
    private void cyclePolicy() {
        Policy next = Policy.values()[(source.policy().ordinal()+1)%Policy.values().length];
        busy = true; rebuildWidgets();
        AudioCacheManager.configure(source, source.kind(), next, updated -> { source = updated; busy = false; rebuildWidgets(); }, this::failed);
    }
    private void depart(boolean delete) {
        busy = true; rebuildWidgets();
        AudioCacheManager.configure(source, Kind.PUBLIC, remember ? (delete ? Policy.DELETE : Policy.KEEP) : Policy.ASK, updated -> {
            if (!delete) { busy = false; onClose(); return; }
            AudioCacheManager.run(() -> { AudioCacheManager.store().clear(source.id()); return true; }, ok -> { busy=false; onClose(); }, this::failed);
        }, this::failed);
    }
    private void confirmDelete() {
        Set<String> ids = Set.copyOf(selected);
        minecraft.gui.setScreen(new ConfirmActionScreen(this, tr("delete_selected"), tr("confirm_delete"), () -> {
            busy = true;
            AudioCacheManager.run(() -> {
                if (mode == Mode.SOURCES) {
                    for (String id : ids) {
                        if (AudioCacheManager.protectedSource(id)) throw new IllegalStateException("Source is in use");
                        AudioCacheManager.store().clear(id);
                    }
                } else {
                    if (AudioCacheManager.protectedSource(source.id())) throw new IllegalStateException("Source is in use");
                    AudioCacheManager.store().delete(source.id(), ids);
                }
                return true;
            }, ignored -> { selected.clear(); reload(); }, this::failed);
        }));
    }
    private void importTracks() {
        Set<String> ids = Set.copyOf(selected); busy=true; status=tr("loading"); rebuildWidgets();
        AudioCacheManager.run(() -> { AudioCacheManager.store().importFriends(source.id(), ids); return true; }, ignored -> {
            FolderTrackLibrary.get().rescan(null); busy=false; status=tr("imported"); rebuildWidgets();
        }, this::failed);
    }
    @Override public void onClose() { if (!busy) minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, mode==Mode.CATEGORY ? tr("choose_kind") : mode==Mode.EXIT ? tr("exit_question") : title, width/2, 10, 0xFFFFFFFF);
        if (source!=null) g.centeredText(font, Component.literal(font.plainSubstrByWidth(sourceName(source), width-20)), width/2, 28, 0xFFCCCCCC);
        g.centeredText(font, status, width/2, mode==Mode.CATEGORY || mode==Mode.EXIT ? 135 : height-111, 0xFFAAAAAA);
    }
}
