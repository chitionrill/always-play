package soke.musicdelay.client.musiclibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Native folder selection; result is delivered on the Minecraft client thread. */
public final class FolderPicker {
    private FolderPicker() {}

    public static void pickFolder(Path initialDirectory, String dialogTitle, Consumer<Path> onResult) {
        Path initial = initialDirectory != null && Files.isDirectory(initialDirectory)
                ? initialDirectory : null;
        NativeTrackDialogs.pickFolder(initial, dialogTitle, onResult);
    }
}
