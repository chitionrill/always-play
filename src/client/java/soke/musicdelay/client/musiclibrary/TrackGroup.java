package soke.musicdelay.client.musiclibrary;

import java.util.ArrayList;
import java.util.List;

/**
 * Узел дерева библиотеки треков.
 *
 * Группа может содержать треки напрямую, подгруппы, или и то и другое сразу
 * (в папке могут лежать отдельные треки вперемешку с подпапками-альбомами).
 *
 * `id` — стабильный идентификатор на основе пути, используется для хранения
 * состояния свёрнуто/развёрнуто для каждого узла отдельно — переиспользуй свой
 * существующий Set<String> для свёрнутых групп, просто убедись, что id вложенных
 * групп уникальны.
 */
public final class TrackGroup {
    private final String id;
    private final String displayName;
    private final List<TrackEntry> tracks = new ArrayList<>();
    private final List<TrackGroup> children = new ArrayList<>();

    public TrackGroup(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<TrackEntry> getTracks() {
        return tracks;
    }

    public List<TrackGroup> getChildren() {
        return children;
    }

    public boolean isEmpty() {
        return tracks.isEmpty() && children.stream().allMatch(TrackGroup::isEmpty);
    }

    /** Количество треков включая все вложенные подгруппы. */
    public int totalTrackCount() {
        int count = tracks.size();
        for (TrackGroup child : children) {
            count += child.totalTrackCount();
        }
        return count;
    }

    /** Собирает все треки этой группы и всех вложенных подгрупп в один плоский список. */
    public List<TrackEntry> collectAllTracks() {
        List<TrackEntry> all = new ArrayList<>(tracks);
        for (TrackGroup child : children) {
            all.addAll(child.collectAllTracks());
        }
        return all;
    }
}