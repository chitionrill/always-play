package soke.musicdelay.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class TrackVolumeManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH = FabricLoader.getInstance().getConfigDir().resolve("music-delay-reducer-track-volumes.json");
    private static final Type MAP_TYPE = new TypeToken<Map<String, Double>>() {}.getType();

    private static final Map<String, Double> cache = load();

    private static Map<String, Double> load() {
        if (Files.exists(DATA_PATH)) {
            try (Reader reader = Files.newBufferedReader(DATA_PATH, StandardCharsets.UTF_8)) {
                Map<String, Double> loaded = GSON.fromJson(reader, MAP_TYPE);
                if (loaded != null) return new HashMap<>(loaded);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return new HashMap<>();
    }

    private static void save() {
        try {
            Files.createDirectories(DATA_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(DATA_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(cache, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static double getGainOffsetDb(Path trackFile) {
        String key = trackFile.getFileName().toString();
        Double cached = cache.get(key);
        if (cached != null) return cached;

        double offset = TrackVolumeAnalyzer.analyzeGainOffsetDb(trackFile);
        cache.put(key, offset);
        save();
        return offset;
    }
}