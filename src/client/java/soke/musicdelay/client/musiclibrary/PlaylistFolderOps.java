package soke.musicdelay.client.musiclibrary;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Создание новой папки для плейлиста. Выбор уже существующей папки делается через
 * FolderPicker.pickFolder(...) — отдельная функция здесь не нужна, это тот же диалог.
 */
public final class PlaylistFolderOps {

    private PlaylistFolderOps() {
    }

    public enum CreateResult {
        CREATED,
        ALREADY_EXISTS,
        FAILED
    }

    /**
     * Создаёт новую папку плейлиста внутри parentDirectory с именем playlistName.
     * Не трогает содержимое, если папка с таким именем уже есть — в этом случае
     * ALREADY_EXISTS не считается ошибкой, вызывающий код решает, использовать ли её как есть.
     */
    public static CreateResult createPlaylistFolder(Path parentDirectory, String playlistName) {
        Path target = parentDirectory.resolve(sanitizeFolderName(playlistName));
        try {
            Files.createDirectory(target);
            return CreateResult.CREATED;
        } catch (FileAlreadyExistsException e) {
            return CreateResult.ALREADY_EXISTS;
        } catch (IOException e) {
            return CreateResult.FAILED;
        }
    }

    /**
     * Убирает из имени плейлиста символы, недопустимые в именах файлов на Windows
     * (и заодно проблемные на других системах), чтобы создание папки не падало
     * из-за того, что игрок ввёл, например, "Рок: лучшее / 2026".
     */
    private static String sanitizeFolderName(String rawName) {
        String cleaned = rawName.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isEmpty() ? "playlist" : cleaned;
    }
}
