package soke.musicdelay.client.musiclibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Сканирует одну корневую папку в дерево TrackGroup.
 *
 * Вызывается только по явным триггерам: добавлен трек, выбрана/изменена папка,
 * выбрана/создана папка плейлиста, или нажата кнопка "Обновить" — не при каждом
 * открытии меню. За подключение этих триггеров к rescanRoot()/TrackLibrary отвечает
 * вызывающий код.
 */
public final class TrackLibraryScanner {

    /** Сюда добавляются новые поддерживаемые форматы. */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("wav", "mp3", "ogg", "flac");

    /** Сканируем саму корневую папку (глубина 1) плюс один уровень подпапок (глубина 2). Глубже — игнорируем. */
    private static final int MAX_DEPTH = 2;

    private TrackLibraryScanner() {
    }

    /**
     * Сканирует одну корневую папку (дефолтная папка мода, выбранная игроком папка, или папка
     * плейлиста — все они сканируются вместе, а не по принципу "или/или").
     *
     * Не проверяет саму папку на валидность (отсутствует/пустая/нечитаема) — это делай отдельно
     * через FolderValidation перед вызовом, чтобы показать диалог подтверждения игроку,
     * а не молча получить здесь пустую группу.
     */
    public static TrackGroup scanRoot(Path root, String rootDisplayName) {
        TrackGroup rootGroup = new TrackGroup(rootDisplayName, rootDisplayName);
        if (root == null || !Files.isDirectory(root)) {
            return rootGroup;
        }
        scanInto(root, rootGroup, rootDisplayName, 1);
        return rootGroup;
    }

    private static void scanInto(Path dir, TrackGroup group, String idPrefix, int depth) {
        List<Path> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream.sorted().toList();
        } catch (IOException e) {
            // Папка стала нечитаемой прямо во время сканирования (сменились права,
            // отключился диск и т.п.) — считаем её пустой, а не роняем всё сканирование.
            return;
        }

        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                if (depth >= MAX_DEPTH) {
                    continue;
                }
                String childId = idPrefix + "/" + entry.getFileName();
                TrackGroup child = new TrackGroup(childId, entry.getFileName().toString());
                scanInto(entry, child, childId, depth + 1);
                if (!child.isEmpty()) {
                    group.getChildren().add(child);
                }
            } else if (isSupportedAudioFile(entry)) {
                group.getTracks().add(TrackEntry.fromFile(entry));
            }
            // Всё остальное (txt, обложки, .DS_Store, неподдерживаемые форматы вроде m4a/wma)
            // молча игнорируется.
        }
    }

    private static boolean isSupportedAudioFile(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }
}