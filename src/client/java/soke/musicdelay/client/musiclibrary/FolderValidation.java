package soke.musicdelay.client.musiclibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Проверяет папку перед тем, как принять её как источник треков.
 * Вызывай сразу после того, как игрок выбрал папку, а также периодически/по требованию
 * для папок, уже сохранённых в конфиге (их могли удалить/переместить вне игры).
 *
 * При любом результате кроме OK нужно показать диалог подтверждения, который игрок обязан
 * закрыть осознанно (не просто всплывающий тост на секунду), прежде чем произойдёт откат
 * на дефолтную папку — как договорились в плане.
 */
public final class FolderValidation {

    public enum Result {
        OK,
        MISSING,       // путь не существует (удалён/перемещён/никогда не существовал)
        NOT_A_FOLDER,  // путь существует, но указывает на файл, а не папку
        UNREADABLE,    // существует, это папка, но её нельзя прочитать (права доступа)
        EMPTY          // существует и читается, но внутри ничего нет
    }

    private FolderValidation() {
    }

    public static Result validate(Path folder) {
        if (folder == null || !Files.exists(folder)) {
            return Result.MISSING;
        }
        if (!Files.isDirectory(folder)) {
            return Result.NOT_A_FOLDER;
        }
        try (Stream<Path> stream = Files.list(folder)) {
            return stream.findAny().isPresent() ? Result.OK : Result.EMPTY;
        } catch (IOException e) {
            return Result.UNREADABLE;
        }
    }
}