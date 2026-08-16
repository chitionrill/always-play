package soke.musicdelay.client.musiclibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Обходит список верхнеуровневых TrackGroup и строит из них плоский список строк
 * (FlattenedTrackRow) для отрисовки в списке с прокруткой.
 *
 * Если группа свёрнута, в список попадает только её заголовок — дочерние подгруппы
 * и треки этой группы не разворачиваются вообще (в том числе вложенные глубже
 * подгруппы, если такие появятся). Так экран не тратит время на построение и
 * измерение строк, которые всё равно не видны.
 */
public final class TrackGroupTreeFlattener {

    private TrackGroupTreeFlattener() {
    }

    /**
     * @param topLevelGroups верхнеуровневые группы (результат TrackLibrary.getTopLevelGroups())
     * @param collapsedIds   id групп, которые сейчас свёрнуты — тот же Set<String>, что уже
     *                       используется для существующих категорий вроде "Vanilla Tracks"
     */
    public static List<FlattenedTrackRow> flatten(List<TrackGroup> topLevelGroups, Set<String> collapsedIds) {
        List<FlattenedTrackRow> rows = new ArrayList<>();
        for (TrackGroup group : topLevelGroups) {
            appendGroup(group, 0, collapsedIds, rows);
        }
        return rows;
    }

    private static void appendGroup(TrackGroup group, int depth, Set<String> collapsedIds, List<FlattenedTrackRow> rows) {
        boolean collapsed = collapsedIds.contains(group.getId());
        rows.add(new FlattenedTrackRow.GroupHeaderRow(group, depth, collapsed));

        if (collapsed) {
            return;
        }

        for (TrackEntry track : group.getTracks()) {
            rows.add(new FlattenedTrackRow.TrackRow(track, depth + 1));
        }
        for (TrackGroup child : group.getChildren()) {
            appendGroup(child, depth + 1, collapsedIds, rows);
        }
    }
}
