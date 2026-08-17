package soke.musicdelay.client.playback;

import soke.musicdelay.client.MusicTracker;
import soke.musicdelay.client.Playlist;
import soke.musicdelay.client.WavPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Desired-state менеджер prefetch'а: на каждое событие, способное изменить будущий
// порядок треков (переключение, смена плейлиста, смена режима), пересчитывает
// "что должно быть прогрето прямо сейчас" через QueuePlanner и приводит реальное
// состояние WavPlayer.preloadedTracks к этому желаемому списку — не более и не менее.
//
// Сам не декодирует и не хранит аудио — это по-прежнему зона ответственности WavPlayer.
// Не решает порядок треков — это по-прежнему зона ответственности TrackOrderManager/
// PlaylistOrderManager/MusicTracker. Только сверяет "что нужно" с "что есть" и просит
// WavPlayer.preload()/cancelPreload() под это подстроиться.
public class TrackPreloadManager {

    // Сколько треков вперёд держим тёплыми. Сам preload дешёвый (open() потокового
    // декодера + разовый RMS-анализ, который потом кэшируется на диске навсегда),
    // поэтому в отличие от исходной идеи с двумя уровнями (start buffer/full prepare)
    // здесь один уровень: либо трек в окне и готовится полностью, либо не готовится совсем.
    public static final int WINDOW = 10;

    private static List<Path> desired = new ArrayList<>();

    // Вызывать при любом событии, способном изменить предсказанную очередь: реальный
    // старт нового трека, ручной skip forward/backward, смена плейлиста, смена режима
    // (custom/both/vanilla), смена trackOrderMode. Дешёвая операция — сама предсказание
    // на WINDOW шагов вперёд не трогает диск, кроме folder-based плейлиста (тот и так
    // пересканируется PlaylistOrderManager.peekNext по своим правилам).
    public static void refresh(MusicTracker tracker, boolean playlistMode, Playlist activePlaylist, String mode) {
        List<Path> newDesired = QueuePlanner.peekUpcomingCustomPaths(tracker, playlistMode, activePlaylist, mode, WINDOW);

        // Снимаем то, что было желаемым, но выпало из нового окна (spam-скип, смена очереди) —
        // это и есть "отмена устаревшей подготовки" из плана.
        for (Path old : desired) {
            if (!newDesired.contains(old)) {
                WavPlayer.cancelPreload(old);
            }
        }

        // Запрашиваем недостающее. WavPlayer.preload() сам no-op, если путь уже прогревается
        // или готов — здесь не нужно отдельно проверять WavPlayer.isReady()/наличие в мапе.
        // Порядок вызовов = порядок постановки в single-thread executor = приоритет по близости
        // к текущему треку, как и требовалось (ближайший трек просится первым).
        for (Path p : newDesired) {
            WavPlayer.preload(p);
        }

        desired = newDesired;
    }

    // Вызывать при выходе из мира/остановке мода — снимает всё окно, чтобы не оставлять
    // висящие preload-задачи и открытые файловые хендлы от предыдущей сессии.
    public static void reset() {
        for (Path p : desired) {
            WavPlayer.cancelPreload(p);
        }
        desired = new ArrayList<>();
    }
}
