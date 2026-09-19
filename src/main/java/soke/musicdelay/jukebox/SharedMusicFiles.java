package soke.musicdelay.jukebox;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Shared disk rules; files are named solely by their content hash. */
public final class SharedMusicFiles {
    public static boolean validHash(String hash) { return hash.matches("[0-9a-f]{64}"); }
    public static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static Path file(Path root, String hash) {
        if (!validHash(hash)) throw new IllegalArgumentException("Invalid track hash");
        return root.resolve(hash + ".audio");
    }
    public static void store(Path root, String hash, byte[] bytes, long quota) throws Exception {
        if (!hash(bytes).equals(hash)) throw new IOException("Audio checksum mismatch");
        Files.createDirectories(root);
        Path target = file(root, hash);
        if (Files.isRegularFile(target) && Files.size(target) == bytes.length
                && hash(Files.readAllBytes(target)).equals(hash)) return;
        long used = 0;
        try (var files = Files.list(root)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) used += Files.size(p);
        }
        if (used + bytes.length > quota) throw new IOException("Audio storage quota exceeded");
        Path temp = Files.createTempFile(root, "incoming-", ".tmp");
        try {
            Files.write(temp, bytes);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private SharedMusicFiles() { }
}
