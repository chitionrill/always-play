package soke.musicdelay.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Playlist {

    public String id;
    public String name;
    public List<PlaylistEntry> entries = new ArrayList<>();

    public Playlist() {
        this.id = UUID.randomUUID().toString();
    }

    public Playlist(String name) {
        this();
        this.name = name;
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

        public static PlaylistEntry ofCustom(String path) {
            return new PlaylistEntry("CUSTOM", path);
        }

        public static PlaylistEntry ofVanilla(String soundLocation) {
            return new PlaylistEntry("VANILLA", soundLocation);
        }
    }
}