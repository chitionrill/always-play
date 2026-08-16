package soke.musicdelay.client.musiclibrary;

import soke.musicdelay.client.BrowsableTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Строит итоговый список строк для браузера треков: применяет поисковый запрос
 * и collapse/expand к старым плоским секциям (Ambient/Discs/Custom) и к дереву
 * папок, объединяя оба источника в один список.
 *
 * Не знает ничего про Minecraft GUI — принимает только данные и возвращает данные,
 * поэтому логику фильтрации можно менять и проверять отдельно от отрисовки экрана.
 */
public final class BrowserTrackFilter {

    private BrowserTrackFilter() {
    }

    public static List<BrowsableTrack> filter(List<BrowsableTrack> allTracks,
                                                List<TrackGroup> folderGroups,
                                                String rawQuery,
                                                Set<String> collapsedHeaderIds) {
        String query = rawQuery.trim().toLowerCase(Locale.ROOT);
        List<BrowsableTrack> filtered = new ArrayList<>();
        List<BrowsableTrack> pendingSection = new ArrayList<>();
        BrowsableTrack pendingHeader = null;

        for (BrowsableTrack track : allTracks) {
            if (track.kind == BrowsableTrack.Kind.HEADER) {
                flushSection(filtered, pendingHeader, pendingSection, collapsedHeaderIds);
                pendingHeader = track;
                pendingSection = new ArrayList<>();
            } else {
                boolean matches = query.isEmpty() || track.displayName.getString().toLowerCase(Locale.ROOT).contains(query);
                if (matches) {
                    if (pendingHeader == null) {
                        filtered.add(track);
                    } else {
                        pendingSection.add(track);
                    }
                }
            }
        }
        flushSection(filtered, pendingHeader, pendingSection, collapsedHeaderIds);

        filtered.addAll(FolderSectionBuilder.build(folderGroups, query, collapsedHeaderIds));

        return filtered;
    }

    private static void flushSection(List<BrowsableTrack> filtered, BrowsableTrack header,
                                      List<BrowsableTrack> section, Set<String> collapsedHeaderIds) {
        if (header != null && !section.isEmpty()) {
            filtered.add(header);
            if (!collapsedHeaderIds.contains(collapseKey(header))) {
                filtered.addAll(section);
            }
        }
    }

    // Заголовки из дерева папок используют groupId как ключ (стабилен и уникален даже для
    // одноимённых подпапок в разных местах); старые заголовки (Ambient/Discs/Custom) groupId
    // не имеют и продолжают использовать текст.
    public static String collapseKey(BrowsableTrack header) {
        return header.groupId != null ? header.groupId : header.displayName.getString();
    }
}
