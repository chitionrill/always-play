package soke.musicdelay.client.musiclibrary;

import java.nio.file.Path;

/**
 * Один отсканированный аудиофайл — просто ссылка на него, файл ещё не загружен/декодирован.
 *
 * Сейчас id — это абсолютный путь к файлу. Это нормально, пока файл лежит на месте, но если
 * файл переместить/переименовать, его id изменится, и плейлист, ссылающийся на старый id,
 * молча потеряет этот трек. Если это станет проблемой — замени `id` на хэш содержимого файла
 * или на UUID, который хранится вместе с записью плейлиста, а не на сырой путь.
 */
public record TrackEntry(String id, Path filePath, String displayName) {

    public static TrackEntry fromFile(Path file) {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String display = dot > 0 ? fileName.substring(0, dot) : fileName;
        return new TrackEntry(file.toAbsolutePath().toString(), file, display);
    }
}