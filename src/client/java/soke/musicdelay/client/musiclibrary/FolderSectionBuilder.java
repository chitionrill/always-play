package soke.musicdelay.client.musiclibrary;

import net.minecraft.network.chat.Component;
import soke.musicdelay.client.BrowsableTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Превращает дерево TrackGroup (результат сканирования папок) в List<BrowsableTrack>,
 * который MusicBrowserScreen уже умеет отрисовывать через TrackListWidget.
 *
 * Поиск и collapse/expand учитываются за один проход: группа добавляется в результат,
 * только если у неё (или у кого-то из потомков) есть хотя бы один трек, подходящий под
 * запрос. Если группа свёрнута, в результат попадает только её заголовок — содержимое
 * (включая вложенные подгруппы) не добавляется вовсе.
 */
public final class FolderSectionBuilder {

    private FolderSectionBuilder() {
    }

    /**
     * @param topLevelGroups   верхнеуровневые группы источника (TrackLibrary.getTopLevelGroups())
     * @param query             текст поиска, уже приведённый к нижнему регистру и обрезанный
     * @param collapsedGroupIds id свёрнутых групп (TrackGroup.getId()) — тот же набор ключей,
     *                          что используется для остальных заголовков в MusicBrowserScreen
     */
    public static List<BrowsableTrack> build(List<TrackGroup> topLevelGroups, String query, Set<String> collapsedGroupIds) {
        List<BrowsableTrack> output = new ArrayList<>();
        for (TrackGroup group : topLevelGroups) {
            appendGroup(group, 0, query, collapsedGroupIds, output);
        }
        return output;
    }

    /** @return true, если группа (или что-то внутри неё) прошло фильтр и было добавлено в output */
    private static boolean appendGroup(TrackGroup group, int depth, String query,
                                        Set<String> collapsedGroupIds, List<BrowsableTrack> output) {
        List<BrowsableTrack> ownTracks = new ArrayList<>();
        for (TrackEntry track : group.getTracks()) {
            if (matchesQuery(track.displayName(), query)) {
                ownTracks.add(BrowsableTrack.folderTrack(track.filePath(), group.getId(), depth + 1));
            }
        }

        List<BrowsableTrack> childOutput = new ArrayList<>();
        boolean anyChildVisible = false;
        for (TrackGroup child : group.getChildren()) {
            if (appendGroup(child, depth + 1, query, collapsedGroupIds, childOutput)) {
                anyChildVisible = true;
            }
        }

        if (ownTracks.isEmpty() && !anyChildVisible) {
            return false;
        }

        output.add(BrowsableTrack.folderHeader(group.getId(), depth, Component.literal(group.getDisplayName())));
        if (!collapsedGroupIds.contains(group.getId())) {
            output.addAll(ownTracks);
            output.addAll(childOutput);
        }
        return true;
    }

    private static boolean matchesQuery(String displayName, String query) {
        return query.isEmpty() || displayName.toLowerCase(Locale.ROOT).contains(query);
    }
}
