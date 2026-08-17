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
        // Для folder-based плейлиста это пересканирует папку — вызывается только здесь,
        // раз за переход к следующему треку (а не на каждый тик), так что случайный файл,
        // докинутый в папку игроком, подхватится сам, без лишней нагрузки на диск.
        List<Playlist.PlaylistEntry> entries = playlist.resolveEntries();
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

    // Предсказывает следующие count записей плейлиста, НЕ трогая реальное состояние
    // (sequentialIndex, shuffleBag, lastPicked) — используется для prefetch.
    // Как и у TrackOrderManager, для RANDOM результат приблизительный, что для целей
    // предзагрузки не критично.
    public static List<Playlist.PlaylistEntry> peekNext(Playlist playlist, String mode, int count) {
        List<Playlist.PlaylistEntry> result = new ArrayList<>();
        if (count <= 0) return result;

        List<Playlist.PlaylistEntry> entries = playlist.resolveEntries();
        if (entries.isEmpty()) return result;

        if (entries.size() == 1) {
            for (int i = 0; i < count; i++) result.add(entries.get(0));
            return result;
        }

        boolean playlistChanged = !playlist.id.equals(lastKnownPlaylistId);
        int simSequentialIndex = playlistChanged ? 0 : sequentialIndex;
        List<Playlist.PlaylistEntry> simShuffleBag = playlistChanged ? new ArrayList<>() : new ArrayList<>(shuffleBag);
        Playlist.PlaylistEntry simLastPicked = playlistChanged ? null : lastPicked;

        for (int i = 0; i < count; i++) {
            Playlist.PlaylistEntry chosen;
            switch (mode) {
                case "SEQUENTIAL": {
                    chosen = entries.get(simSequentialIndex % entries.size());
                    simSequentialIndex = (simSequentialIndex + 1) % entries.size();
                    break;
                }
                case "SHUFFLE_NO_REPEAT": {
                    if (simShuffleBag.isEmpty()) {
                        simShuffleBag.addAll(entries);
                        Collections.shuffle(simShuffleBag, RANDOM);
                        if (simLastPicked != null && simShuffleBag.get(0).equals(simLastPicked) && simShuffleBag.size() > 1) {
                            Collections.swap(simShuffleBag, 0, 1);
                        }
                    }
                    chosen = simShuffleBag.remove(0);
                    break;
                }
                default: {
                    Playlist.PlaylistEntry candidate;
                    int attempts = 0;
                    do {
                        candidate = entries.get(RANDOM.nextInt(entries.size()));
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
        lastKnownPlaylistId = null;
        sequentialIndex = 0;
        shuffleBag.clear();
        lastPicked = null;
    }
}