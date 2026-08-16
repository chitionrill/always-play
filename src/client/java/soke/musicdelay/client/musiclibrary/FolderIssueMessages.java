package soke.musicdelay.client.musiclibrary;

/**
 * Формирует текст для игрока по результату FolderValidation.
 * Отдельный класс, чтобы формулировки не разъезжались по разным местам экрана подтверждения.
 */
public final class FolderIssueMessages {

    private FolderIssueMessages() {
    }

    public static String describe(FolderValidation.Result result) {
        return switch (result) {
            case OK -> "";
            case MISSING -> "Указанная папка не найдена. Возможно, она была удалена или перемещена.";
            case NOT_A_FOLDER -> "Указанный путь ведёт не на папку, а на файл.";
            case UNREADABLE -> "Нет доступа для чтения этой папки. Проверьте права доступа.";
            case EMPTY -> "Папка пуста — в ней не найдено ни одного файла.";
        };
    }
}
