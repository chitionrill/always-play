package soke.musicdelay.client.musiclibrary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Объединяет все источники треков (дефолтная папка мода, выбранная игроком папка, папки
 * каждого плейлиста) в один список верхнеуровневых TrackGroup для MusicBrowserScreen.
 *
 * Все источники всегда сканируются вместе — нет переключателя "либо дефолтная, либо
 * выбранная", они просто все попадают в один общий список.
 *
 * topLevelGroups — volatile, публикуется одной атомарной записью после того, как rescan()
 * полностью пересканировал все источники. Экран читает getTopLevelGroups() без блокировок
 * и никогда не видит список в промежуточном состоянии — тот же приём, что уже используется
 * в CustomTrackManager.tracks. Само сканирование (Files.list по нескольким папкам) —
 * блокирующий диск I/O, вызывающий код обязан запускать rescan() не на потоке рендера
 * (см. FolderTrackLibrary, где это делается в фоновом executor'е).
 */
public final class TrackLibrary {

    private volatile List<TrackGroup> topLevelGroups = List.of();

    public List<TrackGroup> getTopLevelGroups() {
        return topLevelGroups;
    }

    public int totalTrackCount() {
        return topLevelGroups.stream().mapToInt(TrackGroup::totalTrackCount).sum();
    }

    public void rescan(List<TrackSource> sources) {
        List<TrackGroup> scanned = new ArrayList<>();
        for (TrackSource source : sources) {
            scanned.add(TrackLibraryScanner.scanRoot(source.path(), source.displayName()));
        }
        topLevelGroups = Collections.unmodifiableList(scanned);
    }

    /**
     * Одна запись на каждую папку-источник. Список формируется из:
     *  - дефолтной папки мода с треками
     *  - выбранной игроком папки, если она задана
     *  - папки каждого плейлиста, если плейлист работает в режиме "папка"
     * Существующие статичные группы вроде "Vanilla Tracks" / "Music Discs" остаются
     * отдельно — они не привязаны к файловой системе, поэтому через этот сканер вообще не проходят.
     */
    public record TrackSource(Path path, String displayName) {
    }
}