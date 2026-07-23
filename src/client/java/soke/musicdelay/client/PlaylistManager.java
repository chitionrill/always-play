package soke.musicdelay.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import soke.musicdelay.ModConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PlaylistManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE_PATH = FabricLoader.getInstance().getConfigDir().resolve("always-play-playlists.json");
    private static final Type LIST_TYPE = new TypeToken<List<Playlist>>() {}.getType();

    private static List<Playlist> playlists = load();
    private static String activePlaylistId = null;

    private static List<Playlist> load() {
        if (Files.exists(FILE_PATH)) {
            try (Reader reader = Files.newBufferedReader(FILE_PATH, StandardCharsets.UTF_8)) {
                List<Playlist> loaded = GSON.fromJson(reader, LIST_TYPE);
                if (loaded != null) return loaded;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return new ArrayList<>();
    }

    private static void save() {
        try {
            Files.createDirectories(FILE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(playlists, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<Playlist> getAll() {
        return playlists;
    }

    public static Playlist getById(String id) {
        for (Playlist p : playlists) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    public static void add(Playlist playlist) {
        playlists.add(playlist);
        save();
    }

    // Единая точка правды: удаление плейлиста. Проверяем "был ли активным" ДО удаления,
    // и если да — снимаем активность через тот же метод, что и везде (гарантирует что
    // in-memory состояние и ModConfig никогда не разъедутся)
    public static void remove(Playlist playlist) {
        boolean wasActive = playlist.id.equals(activePlaylistId);
        playlists.remove(playlist);
        save();
        if (wasActive) {
            setActivePlaylist(null);
        }
    }

    public static void persist() {
        save();
    }

    // Единственное место, где меняется activePlaylistId — всегда синхронно
    // обновляет и in-memory состояние, и персистентный ModConfig
    public static void setActivePlaylist(String id) {
        activePlaylistId = id;
        ModConfig config = ModConfig.get();
        if (!Objects.equals(config.activePlaylistId, id)) {
            config.activePlaylistId = id;
            config.save();
        }
    }

    public static String getActivePlaylistId() {
        return activePlaylistId;
    }

    public static Playlist getActivePlaylist() {
        return activePlaylistId == null ? null : getById(activePlaylistId);
    }

    public static void clearActivePlaylist() {
        setActivePlaylist(null);
    }
}