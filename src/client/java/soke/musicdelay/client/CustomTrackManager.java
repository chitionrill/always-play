package soke.musicdelay.client;

import net.fabricmc.loader.api.FabricLoader;
import soke.musicdelay.client.musiclibrary.FolderTrackLibrary;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CustomTrackManager {

    private static final Path TRACKS_DIR = FabricLoader.getInstance().getGameDir().resolve("tracks");
    private static final CustomTrackManager INSTANCE = new CustomTrackManager();

    private static final String[] SUPPORTED_EXTENSIONS = { ".wav", ".mp3", ".ogg", ".flac" };

    private static final ExecutorService SCAN_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mdr-track-scan");
        t.setDaemon(true);
        return t;
    });

    // volatile — сканирующий поток публикует новый (иммутабельный) список одной атомарной записью,
    // тик-поток читает его без блокировок. Раньше refresh() делал dir.listFiles() прямо на тик-потоке
    // каждые 100 тиков — блокирующий диск I/O, который на сетевых дисках/больших папках подвешивал игру.
    private volatile List<Path> tracks = List.of();
    private volatile boolean scanning = false;

    public static CustomTrackManager get() {
        return INSTANCE;
    }

    private CustomTrackManager() {
        ensureFolderExists();
    }

    private void ensureFolderExists() {
        try {
            Files.createDirectories(TRACKS_DIR);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Асинхронный запуск скана. Если предыдущий скан ещё не закончился — новый не запускаем
    // (обычное поведение при refresh() каждые 5 секунд с тик-потока), чтобы не плодить очередь задач.
    public void refresh() {
        if (scanning) return;
        scanning = true;
        SCAN_EXECUTOR.submit(() -> {
            try {
                tracks = scanFolder();
                TrackVolumeManager.pruneMissing(); // заодно вычищаем кэш громкости от удалённых файлов
            } finally {
                scanning = false;
            }
        });
    }

    private List<Path> scanFolder() {
        File dir = TRACKS_DIR.toFile();
        File[] files = dir.listFiles();
        if (files == null) return List.of();

        List<Path> found = new ArrayList<>();
        for (File file : files) {
            if (isSupported(file.getName())) {
                found.add(file.toPath());
            }
        }
        return Collections.unmodifiableList(found);
    }

    private boolean isSupported(String fileName) {
        String lower = fileName.toLowerCase();
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    public List<Path> getTracks() {
        return tracks;
    }

    public Path getTracksFolder() {
        return TRACKS_DIR;
    }

    public boolean addTrack(Path sourceFile) {
        try {
            Path target = TRACKS_DIR.resolve(sourceFile.getFileName());
            Files.copy(sourceFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            refresh();
            // Обновляем и дерево вложенных папок — тот же триггер "добавлен трек", что и для
            // плоского списка выше, чтобы новый файл сразу появился в браузере треков.
            FolderTrackLibrary.get().rescan(null);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}