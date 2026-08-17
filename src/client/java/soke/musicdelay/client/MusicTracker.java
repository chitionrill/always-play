package soke.musicdelay.client;

import java.util.ArrayList;
import java.util.List;

public class MusicTracker {
    private static final int MAX_HISTORY = 10;
    private static final MusicTracker INSTANCE = new MusicTracker();

    private final List<UnifiedTrack> history = new ArrayList<>();
    private int currentIndex = -1;
    private boolean navigating = false;

    private UnifiedTrack pendingTrack = null;
    private int pendingCountdown = 0;
    // Отличает "трек ждёт короткую догрузку кэша, но это НЕ история" (обычный/плейлист
    // автоплей) от обычного skip-forward/backward pending — нужно, чтобы tickPending()
    // знал, звать ли playNew() (запишет в историю) или playHistory() (не запишет повторно).
    private boolean pendingIsNew = false;

    public static MusicTracker get() { return INSTANCE; }

    public void onTrackStarted(UnifiedTrack track) {
        if (navigating || track == null) return;
        if (currentIndex < history.size() - 1) {
            history.subList(currentIndex + 1, history.size()).clear();
        }
        history.add(track);
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        currentIndex = history.size() - 1;
    }

    public boolean canGoBack() { return currentIndex > 0; }
    public boolean canGoForward() { return currentIndex < history.size() - 1; }

    public UnifiedTrack getCurrentTrack() {
        return currentIndex >= 0 && currentIndex < history.size() ? history.get(currentIndex) : null;
    }

    public UnifiedTrack getPreviousTrack() {
        currentIndex--;
        return history.get(currentIndex);
    }

    public UnifiedTrack getNextTrack() {
        currentIndex++;
        return history.get(currentIndex);
    }

    // Read-only заглядывание вперёд по уже существующей истории (Previous->Next случай),
    // не трогает currentIndex. Возвращает меньше count элементов, если история закончилась —
    // остаток должен предсказать QueuePlanner через order manager'ы.
    public List<UnifiedTrack> peekForwardHistory(int count) {
        List<UnifiedTrack> result = new ArrayList<>();
        int idx = currentIndex + 1;
        while (result.size() < count && idx < history.size()) {
            result.add(history.get(idx));
            idx++;
        }
        return result;
    }

    public void setNavigating(boolean v) { navigating = v; }
    public boolean isNavigating() { return navigating; }

    public void setPending(UnifiedTrack track, int ticks) {
        pendingTrack = track;
        pendingCountdown = ticks;
        pendingIsNew = false;
    }

    // Тот же механизм короткой догрузки кэша, но для треков, которые ещё НЕ в истории
    // (обычный автоплей / плейлист-автоплей / повторный запуск текущего) — при консьюме
    // должен пойти через playNew(), а не playHistory().
    public void setPendingNew(UnifiedTrack track, int ticks) {
        pendingTrack = track;
        pendingCountdown = ticks;
        pendingIsNew = true;
    }

    public boolean isPendingNew() { return pendingIsNew; }

    public boolean hasPending() { return pendingTrack != null; }

    public void clearPending() {
        pendingTrack = null;
        pendingCountdown = 0;
        pendingIsNew = false;
    }

    public boolean tickPending() {
        if (pendingTrack == null) return false;
        if (pendingCountdown > 0) { pendingCountdown--; return false; }
        return true;
    }

    public UnifiedTrack consumePending() {
        UnifiedTrack t = pendingTrack;
        clearPending();
        return t;
    }
}