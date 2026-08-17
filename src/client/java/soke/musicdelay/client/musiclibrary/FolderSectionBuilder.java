package soke.musicdelay.client.musiclibrary;

import net.minecraft.network.chat.Component;
import soke.musicdelay.client.BrowsableTrack;

import java.util.ArrayList;
import java.util.HashSet;
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
 *
 * Порядок внутри группы: сначала подпапки, потом собственные треки группы — так вложенная
 * структура видна сразу под заголовком, а не перемешивается с треками по алфавиту.
 */
public final class FolderSectionBuilder {

    // Подпапки (не верхнеуровневые группы-источники), которые уже когда-либо сканировались —
    // при первом обнаружении такая подпапка сворачивается по умолчанию. Дальше её состояние
    // целиком в руках игрока (toggleHeaderCollapsed), повторно она не схлопывается сама.
    private static final Set<String> knownSubfolderIds = new HashSet<>();

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
            // Верхнеуровневая группа-источник (например "Твои треки") всегда начинает
            // развёрнутой — сворачиваются по умолчанию только подпапки внутри неё.
            appendGroup(group, 0, query, collapsedGroupIds, output, false);
        }
        return output;
    }

    /** @return true, если группа (или что-то внутри неё) прошло фильтр и было добавлено в output */
    private static boolean appendGroup(TrackGroup group, int depth, String query, Set<String> collapsedGroupIds,
                                       List<BrowsableTrack> output, boolean isSubfolder) {
        if (isSubfolder && knownSubfolderIds.add(group.getId())) {
            collapsedGroupIds.add(group.getId());
        }

        List<BrowsableTrack> ownTracks = new ArrayList<>();
        for (TrackEntry track : group.getTracks()) {
            if (matchesQuery(track.displayName(), query)) {
                ownTracks.add(BrowsableTrack.folderTrack(track.filePath(), group.getId(), depth + 1));
            }
        }

        List<BrowsableTrack> childOutput = new ArrayList<>();
        boolean anyChildVisible = false;
        for (TrackGroup child : group.getChildren()) {
            if (appendGroup(child, depth + 1, query, collapsedGroupIds, childOutput, true)) {
                anyChildVisible = true;
            }
        }

        if (ownTracks.isEmpty() && !anyChildVisible) {
            return false;
        }

        output.add(BrowsableTrack.folderHeader(group.getId(), depth, Component.literal(group.getDisplayName())));
        if (!collapsedGroupIds.contains(group.getId())) {
            output.addAll(childOutput);
            output.addAll(ownTracks);
        }
        return true;
    }

    private static boolean matchesQuery(String displayName, String query) {
        return query.isEmpty() || displayName.toLowerCase(Locale.ROOT).contains(query);
    }
}