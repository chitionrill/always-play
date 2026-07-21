package soke.musicdelay.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class PlaylistOrderManager {
    private static final Random RANDOM = new Random();

    private static String lastKnownPlaylistId = null;
    private static int sequentialIndex = 0;
    private static final List<Playlist.PlaylistEntry> shuffleBag = new ArrayList<>();
    private static Playlist.PlaylistEntry lastPicked = null;

    public static Playlist.PlaylistEntry pickNext(Playlist playlist, String mode) {
        List<Playlist.PlaylistEntry> entries = playlist.entries;
        if (entries.isEmpty()) return null;
        if (entries.size() == 1) return entries.get(0);

        if (!playlist.id.equals(lastKnownPlaylistId)) {
            lastKnownPlaylistId = playlist.id;
            sequentialIndex = 0;
            shuffleBag.clear();
        }

        Playlist.PlaylistEntry chosen;
        switch (mode) {
            case "SEQUENTIAL":
                chosen = entries.get(sequentialIndex % entries.size());
                sequentialIndex = (sequentialIndex + 1) % entries.size();
                break;
            case "SHUFFLE_NO_REPEAT":
                if (shuffleBag.isEmpty()) {
                    shuffleBag.addAll(entries);
                    Collections.shuffle(shuffleBag, RANDOM);
                    if (lastPicked != null && shuffleBag.get(0).equals(lastPicked) && shuffleBag.size() > 1) {
                        Collections.swap(shuffleBag, 0, 1);
                    }
                }
                chosen = shuffleBag.remove(0);
                break;
            default:
                int attempts = 0;
                do {
                    chosen = entries.get(RANDOM.nextInt(entries.size()));
                    attempts++;
                } while (chosen.equals(lastPicked) && attempts < 10);
        }

        lastPicked = chosen;
        return chosen;
    }

    public static void reset() {
        lastKnownPlaylistId = null;
        sequentialIndex = 0;
        shuffleBag.clear();
        lastPicked = null;
    }
}