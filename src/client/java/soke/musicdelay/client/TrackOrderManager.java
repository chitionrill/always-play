package soke.musicdelay.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class TrackOrderManager {
    private static final Random RANDOM = new Random();

    private static List<Path> lastKnownTracks = new ArrayList<>();
    private static int sequentialIndex = 0;
    private static final List<Path> shuffleBag = new ArrayList<>();

    public static Path pickNext(List<Path> tracks, Path lastPlayed, String mode) {
        if (tracks.isEmpty()) return null;
        if (tracks.size() == 1) return tracks.get(0);

        List<Path> sorted = new ArrayList<>(tracks);
        sorted.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));

        boolean listChanged = !sorted.equals(lastKnownTracks);
        if (listChanged) {
            lastKnownTracks = sorted;
            sequentialIndex = 0;
            shuffleBag.clear();
        }

        switch (mode) {
            case "SEQUENTIAL": {
                Path chosen = sorted.get(sequentialIndex % sorted.size());
                sequentialIndex = (sequentialIndex + 1) % sorted.size();
                return chosen;
            }
            case "SHUFFLE_NO_REPEAT": {
                if (shuffleBag.isEmpty()) {
                    shuffleBag.addAll(sorted);
                    Collections.shuffle(shuffleBag, RANDOM);
                    if (lastPlayed != null && shuffleBag.get(0).equals(lastPlayed) && shuffleBag.size() > 1) {
                        Collections.swap(shuffleBag, 0, 1);
                    }
                }
                return shuffleBag.remove(0);
            }
            default: { // RANDOM — как было раньше
                Path chosen;
                do {
                    chosen = sorted.get(RANDOM.nextInt(sorted.size()));
                } while (chosen.equals(lastPlayed));
                return chosen;
            }
        }
    }

    public static void reset() {
        sequentialIndex = 0;
        shuffleBag.clear();
        lastKnownTracks = new ArrayList<>();
    }
}