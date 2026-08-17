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
                int attempts = 0;
                do {
                    chosen = sorted.get(RANDOM.nextInt(sorted.size()));
                    attempts++;
                } while (chosen.equals(lastPlayed) && attempts < 10);
                return chosen;
            }
        }
    }

    // Предсказывает следующие count треков, НЕ трогая реальное состояние (sequentialIndex,
    // shuffleBag) — нужно для prefetch, чтобы заглянуть вперёд, не сбивая порядок, который
    // увидит настоящий pickNext(), когда до этих треков дойдёт очередь.
    // Для режима RANDOM результат приблизительный (реальный вызов использует свежий RANDOM),
    // но это ок для целей prefetch — трек либо пригодится, либо будет просто вытеснен из кэша.
    public static List<Path> peekNext(List<Path> tracks, Path lastPlayed, String mode, int count) {
        List<Path> result = new ArrayList<>();
        if (tracks.isEmpty() || count <= 0) return result;

        List<Path> sorted = new ArrayList<>(tracks);
        sorted.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));

        if (sorted.size() == 1) {
            for (int i = 0; i < count; i++) result.add(sorted.get(0));
            return result;
        }

        boolean listChanged = !sorted.equals(lastKnownTracks);
        int simSequentialIndex = listChanged ? 0 : sequentialIndex;
        List<Path> simShuffleBag = listChanged ? new ArrayList<>() : new ArrayList<>(shuffleBag);
        Path simLastPicked = lastPlayed;

        for (int i = 0; i < count; i++) {
            Path chosen;
            switch (mode) {
                case "SEQUENTIAL": {
                    chosen = sorted.get(simSequentialIndex % sorted.size());
                    simSequentialIndex = (simSequentialIndex + 1) % sorted.size();
                    break;
                }
                case "SHUFFLE_NO_REPEAT": {
                    if (simShuffleBag.isEmpty()) {
                        simShuffleBag.addAll(sorted);
                        Collections.shuffle(simShuffleBag, RANDOM);
                        if (simLastPicked != null && simShuffleBag.get(0).equals(simLastPicked) && simShuffleBag.size() > 1) {
                            Collections.swap(simShuffleBag, 0, 1);
                        }
                    }
                    chosen = simShuffleBag.remove(0);
                    break;
                }
                default: {
                    Path candidate;
                    int attempts = 0;
                    do {
                        candidate = sorted.get(RANDOM.nextInt(sorted.size()));
                        attempts++;
                    } while (candidate.equals(simLastPicked) && attempts < 10);
                    chosen = candidate;
                }
            }
            result.add(chosen);
            simLastPicked = chosen;
        }
        return result;
    }

    public static void reset() {
        sequentialIndex = 0;
        shuffleBag.clear();
        lastKnownTracks = new ArrayList<>();
    }
}