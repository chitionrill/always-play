package soke.musicdelay.client.playback;

import soke.musicdelay.ModConfig;
import soke.musicdelay.client.MusicTracker;
import soke.musicdelay.client.Playlist;
import soke.musicdelay.client.PlaylistOrderManager;
import soke.musicdelay.client.TrackOrderManager;
import soke.musicdelay.client.UnifiedTrack;
import soke.musicdelay.client.musiclibrary.FolderTrackLibrary;
import soke.musicdelay.client.musiclibrary.TrackEntry;
import soke.musicdelay.client.musiclibrary.TrackGroup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Единая точка "какие custom-треки предстоят в ближайшие N шагов", не привязанная
// к какому-то одному источнику (история навигации / обычный автоплей / плейлист).
// Используется TrackPreloadManager, чтобы знать, что готовить заранее.
// Сам не решает порядок — только предсказывает его через уже существующие
// MusicTracker/TrackOrderManager/PlaylistOrderManager, не трогая их реальное состояние.
public class QueuePlanner {

    public static List<Path> peekUpcomingCustomPaths(MusicTracker tracker, boolean playlistMode,
                                                       Playlist activePlaylist, String mode, int count) {
        List<Path> result = new ArrayList<>();
        if (count <= 0) return result;

        // 1) То, что уже известно из истории навигации (игрок листал Previous/Next) —
        // самый надёжный источник, приоритет выше предсказания.
        for (UnifiedTrack t : tracker.peekForwardHistory(count)) {
            if (t.type == UnifiedTrack.Type.CUSTOM) {
                result.add(t.customPath);
            }
            if (result.size() >= count) return result;
        }

        int remaining = count - result.size();
        if (remaining <= 0) return result;

        // 2) Остаток — предсказание через order manager, в зависимости от текущего режима
        ModConfig config = ModConfig.get();
        if (playlistMode && activePlaylist != null) {
            List<Playlist.PlaylistEntry> entries =
                    PlaylistOrderManager.peekNext(activePlaylist, config.trackOrderMode, remaining);
            for (Playlist.PlaylistEntry e : entries) {
                if ("CUSTOM".equals(e.type)) {
                    result.add(Path.of(e.value));
                }
            }
        } else if ("CUSTOM".equals(mode) || "BOTH".equals(mode)) {
            List<Path> pool = collectAllCustomTracks();
            if (!pool.isEmpty()) {
                List<Path> predicted = TrackOrderManager.peekNext(
                        pool, TrackPlaybackService.lastCustomPath, config.trackOrderMode, remaining);
                result.addAll(predicted);
            }
        }
        // VANILLA-режим не даёт custom-путей для preload — vanilla-треки грузит сам движок игры,
        // не WavPlayer, поэтому им эта система не нужна.

        return result;
    }

    // Тот же пул, что и автоплей в PlaybackScheduler: папка мода по умолчанию + выбранная
    // игроком папка (если задана) + все их подпапки.
    public static List<Path> collectAllCustomTracks() {
        List<Path> all = new ArrayList<>();
        for (TrackGroup group : FolderTrackLibrary.get().library().getTopLevelGroups()) {
            for (TrackEntry entry : group.collectAllTracks()) {
                all.add(entry.filePath());
            }
        }
        return all;
    }
}
