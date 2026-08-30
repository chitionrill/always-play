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

    // Отдельное, меньшее окно НАЗАД. Раньше backward-навигация вообще не участвовала
    // в prefetch (только forward), из-за чего переключение назад всегда было "холодным
    // стартом" — с задержкой заметно больше, чем у вперёд, и уязвимым к вытеснению из
    // общего LRU-кэша при активном пролистывании. История уже полностью известна
    // (в отличие от предсказания вперёд), поэтому смысла в большом окне здесь нет —
    // 3 последних трека покрывают типичный сценарий "пара нажатий Previous подряд".
    public static final int BACKWARD_WINDOW = 3;

    private static List<Path> desired = new ArrayList<>();

    // Вызывать при любом событии, способном изменить предсказанную очередь: реальный
    // старт нового трека, ручной skip forward/backward, смена плейлиста, смена режима
    // (custom/both/vanilla), смена trackOrderMode. Дешёвая операция — сама предсказание
    // на WINDOW шагов вперёд не трогает диск, кроме folder-based плейлиста (тот и так
    // пересканируется PlaylistOrderManager.peekNext по своим правилам).
    public static void refresh(MusicTracker tracker, boolean playlistMode, Playlist activePlaylist, String mode) {
        List<Path> forward = QueuePlanner.peekUpcomingCustomPaths(tracker, playlistMode, activePlaylist, mode, WINDOW);
        List<Path> backward = QueuePlanner.peekBehindCustomPaths(tracker, BACKWARD_WINDOW);
        List<Path> newDesired = interleaveByDistance(backward, forward);

        // Снимаем то, что было желаемым, но выпало из нового окна (spam-скип, смена очереди) —
        // это и есть "отмена устаревшей подготовки" из плана.
        for (Path old : desired) {
            if (!newDesired.contains(old)) {
                WavPlayer.cancelPreload(old);
            }
        }

        // Запрашиваем недостающее. WavPlayer.preload() сам no-op, если путь уже прогревается
        // или готов. Порядок вызовов = порядок постановки в single-thread executor = приоритет:
        // ближайшие соседи текущего трека в обе стороны просятся первыми, дальние forward-
        // предсказания — последними.
        for (Path p : newDesired) {
            WavPlayer.preload(p);
        }

        desired = newDesired;
    }

    // Чередует backward/forward по расстоянию от текущего трека (backward1, forward1,
    // backward2, forward2, ...), а не "сначала всё назад, потом всё вперёд" — иначе близкий
    // backward-трек ждал бы своей очереди в executor'е позади десяти дальних forward-треков.
    private static List<Path> interleaveByDistance(List<Path> backward, List<Path> forward) {
        List<Path> result = new ArrayList<>(backward.size() + forward.size());
        int max = Math.max(backward.size(), forward.size());
        for (int i = 0; i < max; i++) {
            if (i < backward.size()) result.add(backward.get(i));
            if (i < forward.size()) result.add(forward.get(i));
        }
        return result;
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