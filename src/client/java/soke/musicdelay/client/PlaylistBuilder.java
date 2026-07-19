package soke.musicdelay.client;

import java.util.ArrayList;
import java.util.List;

public class PlaylistBuilder {

    private static List<Playlist.PlaylistEntry> draftEntries = null;

    public static boolean isBuilding() {
        return draftEntries != null;
    }

    public static void startBuilding() {
        draftEntries = new ArrayList<>();
    }

    public static void addEntry(BrowsableTrack track) {
        if (draftEntries == null) startBuilding();
        draftEntries.add(track.toPlaylistEntry());
    }

    public static int getEntryCount() {
        return draftEntries == null ? 0 : draftEntries.size();
    }

    public static void cancelBuilding() {
        draftEntries = null;
    }

    public static Playlist finishBuilding(String name) {
        if (draftEntries == null) return null;
        Playlist playlist = new Playlist(name);
        playlist.entries.addAll(draftEntries);
        PlaylistManager.add(playlist);
        draftEntries = null;
        return playlist;
    }
}