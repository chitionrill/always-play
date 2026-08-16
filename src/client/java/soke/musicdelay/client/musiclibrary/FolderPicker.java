package soke.musicdelay.client.musiclibrary;

import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Открывает нативный диалог выбора папки через TinyFileDialogs — тот же способ, что уже
 * используется для выбора файла трека (ConfigScreen.openFileChooser()), только для папки.
 *
 * Диалог блокирующий, поэтому запускается в отдельном потоке; результат возвращается
 * обратно на клиентский поток игры через Minecraft.execute(...), чтобы применение выбранного
 * пути к состоянию мода было безопасным.
 */
public final class FolderPicker {

    private FolderPicker() {
    }

    /**
     * @param initialDirectory папка, которая будет открыта в диалоге изначально
     * @param dialogTitle      заголовок окна диалога
     * @param onResult         вызывается на клиентском потоке; получает выбранный путь,
     *                         либо null, если игрок закрыл диалог без выбора
     */
    public static void pickFolder(Path initialDirectory, String dialogTitle, Consumer<Path> onResult) {
        new Thread(() -> {
            String defaultPath = (initialDirectory != null && Files.isDirectory(initialDirectory))
                    ? initialDirectory.toAbsolutePath().toString()
                    : null;

            String result = TinyFileDialogs.tinyfd_selectFolderDialog(dialogTitle, defaultPath);
            Path selected = result != null ? Path.of(result) : null;

            net.minecraft.client.Minecraft.getInstance().execute(() -> onResult.accept(selected));
        }, "mdr-folder-chooser").start();
    }
}