package soke.musicdelay.client;

import soke.musicdelay.client.musiclibrary.TrackEntry;
import soke.musicdelay.client.musiclibrary.TrackGroup;
import soke.musicdelay.client.musiclibrary.TrackLibraryScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Playlist {

    public String id;
    public String name;
    public List<PlaylistEntry> entries = new ArrayList<>();

    // Если задан — плейлист привязан к папке целиком, а не к ручному списку entries.
    // entries в этом случае игнорируется при воспроизведении, треки определяются
    // сканированием папки заново каждый раз через resolveEntries() — так добавленные
    // в папку файлы подхватываются сами, без редактирования плейлиста.
    public String folderPath = null;

    public Playlist() {
        this.id = UUID.randomUUID().toString();
    }

    public Playlist(String name) {
        this();
        this.name = name;
    }

    public boolean isFolderBased() {
        return folderPath != null && !folderPath.isBlank();
    }

    // Возвращает треки плейлиста: entries как есть для обычного плейлиста, либо
    // результат свежего сканирования папки для folder-based. Сканирование — блокирующий
    // доступ к диску, вызывать не с потока рендера (как и остальные сканеры в musiclibrary).
    public List<PlaylistEntry> resolveEntries() {
        if (!isFolderBased()) {
            return entries;
        }

        TrackGroup scanned = TrackLibraryScanner.scanRoot(Path.of(folderPath), name);
        List<PlaylistEntry> resolved = new ArrayList<>();
        for (TrackEntry track : scanned.collectAllTracks()) {
            resolved.add(PlaylistEntry.ofCustom(track.filePath().toString()));
        }
        return resolved;
    }

    // Одна запись в плейлисте — либо путь к кастомному файлу, либо идентификатор ванильного звука
    public static class PlaylistEntry {
        public String type; // "VANILLA" или "CUSTOM"
        public String value; // путь к файлу, либо Identifier ванильного звука в виде строки

        public PlaylistEntry() {}

        public PlaylistEntry(String type, String value) {
            this.type = type;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PlaylistEntry other)) return false;
            return java.util.Objects.equals(type, other.type) && java.util.Objects.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(type, value);
        }

        public static PlaylistEntry ofCustom(String path) {
            return new PlaylistEntry("CUSTOM", path);
        }

        public static PlaylistEntry ofVanilla(String soundLocation) {
            return new PlaylistEntry("VANILLA", soundLocation);
        }
        public UnifiedTrack toUnifiedTrack() {
            if ("CUSTOM".equals(type)) {
                return UnifiedTrack.ofCustom(java.nio.file.Path.of(value));
            } else {
                net.minecraft.client.resources.sounds.Sound sound =
                        VanillaTrackRegistry.findSoundByLocation(net.minecraft.resources.Identifier.parse(value));
                return sound != null ? UnifiedTrack.ofVanilla(sound) : null;
            }
        }
    }
}