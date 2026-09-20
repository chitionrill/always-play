package soke.musicdelay.client.cache;

import soke.musicdelay.jukebox.SharedMusicFiles;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.sound.sampled.AudioSystem;

/** Disk operations run on the shared audio file executor, never on the render thread. */
public final class AudioCacheStore {
    public enum Kind { WORLD, FRIENDS, PUBLIC, UNKNOWN, LEGACY }
    public enum Policy { ASK, KEEP, DELETE }
    public record Source(String id, String name, Kind kind, Policy policy) {}
    public record Track(String hash, String title, long bytes) {}
    private final Path root, friends;
    public AudioCacheStore(Path game) {
        root = game.resolve("always-play-audio-cache").toAbsolutePath().normalize();
        friends = game.resolve("always-play-friends-music").toAbsolutePath().normalize();
    }
    public Path friends() { return friends; }
    public Path directory(String id) {
        if (id.equals("legacy")) return root;
        if (!SharedMusicFiles.validHash(id)) throw new IllegalArgumentException("Invalid source");
        return root.resolve("sources").resolve(id);
    }
    private static Properties read(Path path) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) try (var in = Files.newInputStream(path)) { p.load(in); }
        return p;
    }
    private static void write(Path path, Properties p) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), "metadata-", ".tmp");
        try {
            try (var out = Files.newOutputStream(tmp)) { p.store(out, "Always Play"); }
            try { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    public Source open(String identity, String name, Kind initial) throws Exception {
        String id = SharedMusicFiles.hash(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path meta = directory(id).resolve("source.properties");
        Source s;
        if (Files.exists(meta)) s = source(id);
        else { s = new Source(id, name, initial, Policy.ASK); save(s); }
        return s;
    }
    public Source source(String id) throws IOException {
        if (id.equals("legacy")) return new Source(id, "", Kind.LEGACY, Policy.KEEP);
        Properties p = read(directory(id).resolve("source.properties"));
        return new Source(id, p.getProperty("name", id.substring(0, 12)),
                Kind.valueOf(p.getProperty("kind", "UNKNOWN")), Policy.valueOf(p.getProperty("policy", "ASK")));
    }
    public void save(Source s) throws IOException {
        Properties p = new Properties(); p.setProperty("name", s.name());
        p.setProperty("kind", s.kind().name()); p.setProperty("policy", s.policy().name());
        write(directory(s.id()).resolve("source.properties"), p);
    }
    public List<Source> sources() throws IOException {
        List<Source> result = new ArrayList<>();
        if (!tracks("legacy").isEmpty()) result.add(source("legacy"));
        Path dir = root.resolve("sources");
        if (Files.isDirectory(dir)) try (var paths = Files.list(dir)) {
            for (Path p : paths.toList()) if (SharedMusicFiles.validHash(p.getFileName().toString()) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                result.add(source(p.getFileName().toString()));
            }
        }
        result.sort(Comparator.comparing(Source::name)); return List.copyOf(result);
    }
    public List<Track> tracks(String id) throws IOException {
        Path dir = directory(id); List<Track> result = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        Properties titles = read(dir.resolve("tracks.properties"));
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".audio") && SharedMusicFiles.validHash(n.substring(0, n.length()-6)) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    String hash = n.substring(0, n.length()-6);
                    result.add(new Track(hash, titles.getProperty(hash, hash.substring(0, 12)), Files.size(p)));
                }
            }
        }
        result.sort(Comparator.comparing(Track::title)); return List.copyOf(result);
    }
    public void title(String id, String hash, String title) throws IOException {
        if (!SharedMusicFiles.validHash(hash) || title == null || title.isBlank()) return;
        Path p = directory(id).resolve("tracks.properties"); Properties props = read(p);
        if (title.equals(props.getProperty(hash))) return;
        props.setProperty(hash, title.substring(0, Math.min(128, title.length()))); write(p, props);
    }
    public void store(String id, String hash, byte[] bytes, boolean background) throws Exception {
        long quota = (background ? 448L : 512L) * 1024 * 1024;
        Path target = SharedMusicFiles.file(directory(id), hash);
        long used = 0;
        for (Source s : sources()) for (Track t : tracks(s.id())) used += t.bytes();
        long previous = Files.isRegularFile(target) ? Files.size(target) : 0;
        if (used - previous + bytes.length > quota) throw new IOException("Client audio cache full; use audio cache settings");
        SharedMusicFiles.store(directory(id), hash, bytes, Long.MAX_VALUE);
    }
    public void delete(String id, Collection<String> hashes) throws IOException {
        Path dir = directory(id);
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(root.resolve("sources")) || Files.isSymbolicLink(dir))
            throw new IOException("Refusing linked cache directory");
        Properties titles = read(dir.resolve("tracks.properties"));
        for (String hash : hashes) { Files.deleteIfExists(SharedMusicFiles.file(dir, hash)); titles.remove(hash); }
        if (Files.isDirectory(dir)) write(dir.resolve("tracks.properties"), titles);
    }
    public void clear(String id) throws IOException { delete(id, tracks(id).stream().map(Track::hash).toList()); }
    public void importFriends(String id, Collection<String> hashes) throws Exception {
        Kind kind = source(id).kind();
        if (kind != Kind.FRIENDS && kind != Kind.WORLD) throw new IOException("Only local/friends' worlds can be imported");
        Files.createDirectories(friends);
        Map<String, Track> tracks = new HashMap<>(); for (Track t : tracks(id)) tracks.put(t.hash(), t);
        for (String hash : hashes) {
            Track track = tracks.get(hash); if (track == null) throw new IOException("Track is no longer cached");
            Path input = SharedMusicFiles.file(directory(id), hash);
            byte[] data = Files.readAllBytes(input);
            if (!SharedMusicFiles.hash(data).equals(hash)) throw new IOException("Audio checksum mismatch");
            String ext = AudioSystem.getAudioFileFormat(new ByteArrayInputStream(data)).getType().getExtension().toLowerCase(Locale.ROOT);
            if (!Set.of("wav", "mp3", "ogg", "flac").contains(ext)) throw new IOException("Unsupported audio format: " + ext);
            String name = track.title().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
            if (name.length() > 80) name = name.substring(0, 80);
            String suffix = " - " + hash + "." + ext;
            Path target = friends.resolve(name + suffix);
            try (var files = Files.list(friends)) { if (files.anyMatch(p -> p.getFileName().toString().endsWith(suffix))) continue; }
            Path tmp = Files.createTempFile(friends, "import-", ".tmp");
            try { Files.write(tmp, data); Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(tmp); }
        }
    }
}
