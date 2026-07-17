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

    public UnifiedTrack getPreviousTrack() {
        currentIndex--;
        return history.get(currentIndex);
    }

    public UnifiedTrack getNextTrack() {
        currentIndex++;
        return history.get(currentIndex);
    }

    public void setNavigating(boolean v) { navigating = v; }
    public boolean isNavigating() { return navigating; }

    public void setPending(UnifiedTrack track, int ticks) {
        pendingTrack = track;
        pendingCountdown = ticks;
    }

    public boolean hasPending() { return pendingTrack != null; }

    public void clearPending() {
        pendingTrack = null;
        pendingCountdown = 0;
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