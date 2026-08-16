package soke.musicdelay.client.gui;

import soke.musicdelay.client.BrowsableTrack;

/**
 * Действия, которые может выполнить строка списка треков. Реализуется MusicBrowserScreen,
 * чтобы TrackListWidget не знал ничего о плейлистах, поиске или других деталях экрана —
 * только вызывал нужный колбэк по клику.
 */
public interface TrackRowCallbacks {

    boolean isSelected(BrowsableTrack track);

    void toggleSelected(BrowsableTrack track);

    void onPlay(BrowsableTrack track);

    void onAdd(BrowsableTrack track);

    void onToggleHeader(BrowsableTrack header);

    boolean isHeaderCollapsed(BrowsableTrack header);
}
