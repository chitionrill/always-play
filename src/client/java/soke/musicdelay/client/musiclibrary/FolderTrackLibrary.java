package soke.musicdelay.client.musiclibrary;

import soke.musicdelay.ModConfig;
import soke.musicdelay.client.CustomTrackManager;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Синглтон-держатель TrackLibrary, аналогично CustomTrackManager.get(). Собирает источники
 * (папка мода по умолчанию + выбранная игроком папка из ModConfig) и пересканирует их
 * в фоновом потоке — так же, как CustomTrackManager.refresh() не блокирует поток рендера.
 */
public final class FolderTrackLibrary {

    private static final FolderTrackLibrary INSTANCE = new FolderTrackLibrary();

    private static final ExecutorService SCAN_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mdr-folder-tree-scan");
        t.setDaemon(true);
        return t;
    });

    private final TrackLibrary library = new TrackLibrary();
    private volatile boolean scanning = false;

    private FolderTrackLibrary() {
    }

    public static FolderTrackLibrary get() {
        return INSTANCE;
    }

    public TrackLibrary library() {
        return library;
    }

    /** Путь выбранной игроком папки из конфига, либо null, если она не задана. */
    public Path getChosenFolder() {
        String raw = ModConfig.get().customTracksFolder;
        return raw != null && !raw.isBlank() ? Paths.get(raw) : null;
    }

    /**
     * Проверяет папку и, если она валидна, сохраняет путь в конфиг и запускает пересканирование.
     * Если папка невалидна — конфиг не трогается, вызывающий код показывает
     * FolderIssueConfirmScreen и не применяет выбор.
     */
    public FolderValidation.Result applyChosenFolder(Path chosenFolder, Runnable onRescanComplete) {
        FolderValidation.Result result = FolderValidation.validate(chosenFolder);
        if (result != FolderValidation.Result.OK) {
            return result;
        }
        ModConfig.get().customTracksFolder = chosenFolder.toAbsolutePath().toString();
        ModConfig.get().save();
        rescan(onRescanComplete);
        return result;
    }

    /** Сбрасывает выбранную папку — библиотека сканирует только папку мода по умолчанию. */
    public void clearChosenFolder(Runnable onRescanComplete) {
        ModConfig.get().customTracksFolder = null;
        ModConfig.get().save();
        rescan(onRescanComplete);
    }

    /**
     * Асинхронно пересобирает библиотеку из дефолтной папки мода и выбранной игроком папки
     * (если она задана и валидна на момент сканирования — невалидную просто пропускаем молча,
     * само уведомление игроку уже произошло в applyChosenFolder при её выборе).
     *
     * Если предыдущее сканирование ещё не завершилось — новый запуск не начинаем, как и
     * CustomTrackManager.refresh(), чтобы не плодить очередь фоновых задач.
     *
     * @param onRescanComplete вызывается по завершении, уже на потоке сканирования — если
     *                         нужно применить результат на клиентском потоке (например,
     *                         обновить открытый экран), вызывающий код сам заворачивает
     *                         колбэк в client.execute(...).
     */
    public void rescan(Runnable onRescanComplete) {
        if (scanning) return;
        scanning = true;
        SCAN_EXECUTOR.submit(() -> {
            try {
                List<TrackLibrary.TrackSource> sources = new ArrayList<>();
                sources.add(new TrackLibrary.TrackSource(CustomTrackManager.get().getTracksFolder(), "Твои треки"));

                Path chosen = getChosenFolder();
                if (chosen != null && FolderValidation.validate(chosen) == FolderValidation.Result.OK) {
                    sources.add(new TrackLibrary.TrackSource(chosen, "Выбранная папка"));
                }

                library.rescan(sources);
            } finally {
                scanning = false;
                if (onRescanComplete != null) {
                    onRescanComplete.run();
                }
            }
        });
    }
}