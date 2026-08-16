package soke.musicdelay.client.musiclibrary;

/**
 * Одна строка в плоском представлении дерева треков, готовая для отрисовки в списке.
 * Дерево TrackGroup само по себе рекурсивное и неудобно для UI-списка с прокруткой —
 * этот класс превращает его в обычный List, который можно просто пройти циклом
 * и отрисовать построчно, не думая о рекурсии на стороне экрана.
 */
public sealed interface FlattenedTrackRow {

    /** Насколько строка вложена — 0 для верхнего уровня, 1 для подгруппы/трека внутри неё, и т.д. Влияет на отступ слева. */
    int depth();

    /** Строка-заголовок группы (папки). depth — это глубина самой группы. */
    record GroupHeaderRow(TrackGroup group, int depth, boolean collapsed) implements FlattenedTrackRow {
    }

    /** Строка одного трека. depth — глубина группы, в которой он лежит. */
    record TrackRow(TrackEntry track, int depth) implements FlattenedTrackRow {
    }
}
