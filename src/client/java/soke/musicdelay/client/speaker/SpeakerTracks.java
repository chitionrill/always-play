package soke.musicdelay.client.speaker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import soke.musicdelay.client.musiclibrary.FolderTrackLibrary;

/** Local allowlist: network references never become filesystem paths. */
public final class SpeakerTracks {
    public record Entry(String key, Path path, String name) { }
    private static volatile Map<String,Entry> entries=Map.of();
    private static volatile List<Entry> sorted=List.of();
    private static volatile Object lastGroups;
    private static final java.util.concurrent.atomic.AtomicBoolean rebuilding=new java.util.concurrent.atomic.AtomicBoolean();
    private SpeakerTracks() { }
    public static Entry find(String key) { return entries.get(key); }
    public static List<Entry> list() {
        return sorted;
    }
    public static void refresh() {
        FolderTrackLibrary.get().rescan(SpeakerTracks::poll);
        poll();
    }
    public static void poll() {
        if (FolderTrackLibrary.get().library().getTopLevelGroups()==lastGroups || !rebuilding.compareAndSet(false,true)) return;
        java.util.concurrent.CompletableFuture.runAsync(()->{
            try { rebuild(); } finally { rebuilding.set(false); }
        });
    }
    private static void rebuild() {
        var groups=FolderTrackLibrary.get().library().getTopLevelGroups();
        Map<String,Entry> next=new HashMap<>();
        for(var group:groups) for(var t:group.collectAllTracks()) {
            Path p=t.filePath().toAbsolutePath().normalize();
            try {
                String key="local:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p.toString().getBytes(StandardCharsets.UTF_8)));
                next.put(key,new Entry(key,p,t.displayName()));
            } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
        entries=Map.copyOf(next);
        sorted=next.values().stream().sorted(Comparator.comparing(Entry::name).thenComparing(Entry::key)).toList();
        lastGroups=groups;
    }
}
