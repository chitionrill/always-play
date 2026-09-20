package soke.musicdelay.client.cache;

import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import soke.musicdelay.MusicDelayReducer;
import soke.musicdelay.client.gui.AudioCacheScreen;
import soke.musicdelay.client.jukebox.SharedJukeboxClient;
import soke.musicdelay.network.SharedMusicPacket;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import static soke.musicdelay.client.cache.AudioCacheStore.*;

public final class AudioCacheManager {
    private static AudioCacheStore store;
    private static volatile Source active;
    private static final Map<String, String> knownTitles = new HashMap<>();
    private static final Queue<Source> exits = new ArrayDeque<>();
    private static long connection, joined, lastIdentityRequest;
    private static boolean connecting, resolving, asked;
    private static String address, name;
    public static AudioCacheStore store() {
        if (store == null) store = new AudioCacheStore(Minecraft.getInstance().gameDirectory.toPath());
        return store;
    }
    public static Path friendsDirectory() { return Minecraft.getInstance().gameDirectory.toPath().resolve("always-play-friends-music"); }
    public static Source active() { return active; }
    public static Path directory() {
        if (active == null) throw new IllegalStateException("Audio cache identity is not ready");
        return store().directory(active.id());
    }
    public static void join() {
        connection++; active = null; connecting = true; resolving = false; asked = false;
        joined = System.nanoTime(); lastIdentityRequest = joined; knownTitles.clear();
        var mc = Minecraft.getInstance(); var server = mc.getCurrentServer();
        address = server == null && mc.getSingleplayerServer() != null
                ? mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString()
                : server == null ? "local" : server.ip;
        name = server == null ? "" : server.name;
        store();
        if (SharedJukeboxClient.supported()) ClientPlayNetworking.send(SharedMusicPacket.simple("cache_identity", "", ""));
    }
    public static void identity(String worldId) {
        if (!connecting || resolving || active != null) return;
        try { UUID.fromString(worldId); } catch (Exception e) { return; }
        resolve("world:" + worldId);
    }
    private static void resolve(String identity) {
        resolving = true; long epoch = connection;
        var mc = Minecraft.getInstance(); var server = mc.getSingleplayerServer();
        Kind kind = server != null ? Kind.WORLD : Kind.UNKNOWN;
        String label = server != null ? server.getWorldData().getLevelName() : name;
        // An unknown public/friends category is still isolated from all other worlds.
        run(() -> store().open(identity, label, kind), source -> {
            if (connection != epoch || !connecting) return;
            active = source;
        }, error -> { if (connection == epoch) { resolving = false; joined = System.nanoTime(); } });
    }
    public static void disconnect() {
        Source old = active; connection++; active = null; connecting = false; resolving = false;
        if (old != null && old.kind() == Kind.PUBLIC) {
            if (old.policy() == Policy.DELETE) clearDisconnected(old);
            else if (old.policy() == Policy.ASK) run(() -> !store().tracks(old.id()).isEmpty(),
                    hasTracks -> { if (hasTracks) exits.add(old); }, error -> exits.add(old));
        }
    }
    public static void tick(Minecraft mc) {
        if (connecting && active == null && !resolving && SharedJukeboxClient.supported()
                && System.nanoTime() - lastIdentityRequest > 1_000_000_000L) {
            lastIdentityRequest = System.nanoTime();
            ClientPlayNetworking.send(SharedMusicPacket.simple("cache_identity", "", ""));
        }
        if (connecting && active == null && !resolving && SharedJukeboxClient.supported()
                && System.nanoTime() - joined > 5_000_000_000L) resolve("address:" + address);
        if (mc.level != null && active != null && active.kind() == Kind.UNKNOWN && !asked && mc.gui.screen() == null) {
            asked = true; mc.gui.setScreen(AudioCacheScreen.category(null, active));
        }
        if (mc.level == null && !connecting && !exits.isEmpty()
                && (mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
                    || mc.gui.screen() instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
                    || mc.gui.screen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen)) {
            mc.gui.setScreen(AudioCacheScreen.departure(mc.gui.screen(), exits.remove()));
        }
    }
    public static void configure(Source source, Kind kind, Policy policy, Consumer<Source> done, Consumer<String> error) {
        Source updated = new Source(source.id(), source.name(), kind, policy);
        run(() -> { store().save(updated); return updated; }, value -> {
            if (active != null && active.id().equals(value.id())) active = value;
            done.accept(value);
        }, error);
    }
    public static boolean protectedSource(String id) { return active != null && active.id().equals(id); }
    public static void clearDisconnected(Source source) {
        run(() -> { if (!protectedSource(source.id())) store().clear(source.id()); return true; }, ignored -> {}, error -> {
            exits.add(source); MusicDelayReducer.LOGGER.warn("Cannot clear disconnected audio cache: {}", error);
        });
    }
    public static void remember(String hash, String title) {
        if (active == null || title == null || title.isBlank()) return;
        if (title.equals(knownTitles.get(hash))) return;
        if (knownTitles.size() >= 1024) return;
        knownTitles.put(hash, title);
        String id = active.id();
        run(() -> { store().title(id, hash, title); return true; }, ignored -> {}, ignored -> {});
    }
    @FunctionalInterface public interface Operation<T> { T run() throws Exception; }
    public static <T> void run(Operation<T> operation, Consumer<T> success, Consumer<String> failure) {
        try { SharedJukeboxClient.fileTask(() -> {
            try { T result = operation.run(); Minecraft.getInstance().execute(() -> success.accept(result)); }
            catch (Exception e) {
                MusicDelayReducer.LOGGER.warn("Audio cache operation failed", e);
                Minecraft.getInstance().execute(() -> failure.accept(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }); } catch (java.util.concurrent.RejectedExecutionException e) {
            Minecraft.getInstance().execute(() -> failure.accept("Audio file queue is busy"));
        }
    }
    private AudioCacheManager() {}
}
