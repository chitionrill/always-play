package soke.musicdelay.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CustomTrackManager {

    private static final Path TRACKS_DIR = FabricLoader.getInstance().getGameDir().resolve("tracks");
    private static final CustomTrackManager INSTANCE = new CustomTrackManager();

    private static final String[] SUPPORTED_EXTENSIONS = { ".wav", ".mp3", ".ogg", ".flac" };

    private final List<Path> tracks = new ArrayList<>();

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

    public void refresh() {
        tracks.clear();
        File dir = TRACKS_DIR.toFile();
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (isSupported(file.getName())) {
                tracks.add(file.toPath());
            }
        }
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
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}