package soke.musicdelay.client.playback;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.Music;
import soke.musicdelay.ModConfig;
import soke.musicdelay.client.IMusicManagerMixin;
import soke.musicdelay.client.MusicTracker;
import soke.musicdelay.client.Playlist;
import soke.musicdelay.client.PlaylistOrderManager;
import soke.musicdelay.client.TrackOrderManager;
import soke.musicdelay.client.TrackVolumeManager;
import soke.musicdelay.client.UnifiedTrack;
import soke.musicdelay.client.WavPlayer;
import soke.musicdelay.client.musiclibrary.FolderTrackLibrary;
import soke.musicdelay.client.musiclibrary.TrackEntry;
import soke.musicdelay.client.musiclibrary.TrackGroup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// Отвечает за "что играет дальше": ручные skip forward/backward,
// обычный автоплей и автоплей плейлиста. Общее состояние (autoplayCountdown,
// запланированный трек) живёт здесь, потому что skip и автоплей делят его —
// именно сюда в будущем (1.5) будет встраиваться событийная система.
public class PlaybackScheduler {

    private static final Random RANDOM = new Random();
    private static final int PLAYLIST_MIN_PRELOAD_TICKS = 40;

    public static int autoplayCountdown = 0;
    public static Path plannedAutoplayPath = null;
    public static boolean plannedAutoplayIsVanilla = false;
    public static Playlist.PlaylistEntry plannedPlaylistEntry = null;

    public static void handleSkipForward(IMusicManagerMixin mixin, MusicTracker tracker, String mode,
                                         boolean playlistMode, Playlist activePlaylist, ModConfig config, int skipDelayTicks) {
        tracker.clearPending();
        if (!"CUSTOM".equals(mode) || playlistMode) mixin.mdr$stopAndBlock();

        if (tracker.canGoForward()) {
            UnifiedTrack next = tracker.getNextTrack();
            if (skipDelayTicks <= 0) {
                TrackPlaybackService.playHistory(mixin, next);
            } else {
                WavPlayer.stop();
                if (next.type == UnifiedTrack.Type.CUSTOM) {
                    WavPlayer.preload(next.customPath);
                }
                tracker.setPending(next, skipDelayTicks);
            }
        } else if (playlistMode) {
            WavPlayer.stop();
            TrackPlaybackService.playing = false;
            autoplayCountdown = primePlaylistEntryAndGetDelay(activePlaylist, config, skipDelayTicks);
        } else if ("VANILLA".equals(mode)) {
            mixin.mdr$unblock(skipDelayTicks);
        } else {
            WavPlayer.stop();
            TrackPlaybackService.playing = false;
            autoplayCountdown = skipDelayTicks;
            plannedAutoplayPath = null;
        }
    }

    public static void handleSkipBackward(IMusicManagerMixin mixin, MusicTracker tracker, String mode,
                                          boolean playlistMode, int skipDelayTicks) {
        if (!tracker.canGoBack()) return;
        tracker.clearPending();
        if (!"CUSTOM".equals(mode) || playlistMode) mixin.mdr$stopAndBlock();
        UnifiedTrack previous = tracker.getPreviousTrack();
        if (skipDelayTicks <= 0) {
            TrackPlaybackService.playHistory(mixin, previous);
        } else {
            WavPlayer.stop();
            if (previous.type == UnifiedTrack.Type.CUSTOM) {
                WavPlayer.preload(previous.customPath);
            }
            tracker.setPending(previous, skipDelayTicks);
        }
    }

    public static void tickPending(IMusicManagerMixin mixin, MusicTracker tracker) {
        if (tracker.hasPending() && tracker.tickPending()) {
            UnifiedTrack pending = tracker.consumePending();
            TrackPlaybackService.playHistory(mixin, pending);
        }
    }

    public static void tickAutoplay(Minecraft client, IMusicManagerMixin mixin, MusicTracker tracker,
                                    ModConfig config, String mode, boolean repeatOne) {
        if (tracker.hasPending()) return;

        boolean stillPlaying = WavPlayer.isBusy() || mixin.mdr$isVanillaActive();

        if (TrackPlaybackService.playing && !stillPlaying) {
            TrackPlaybackService.playing = false;
            if (repeatOne) {
                replayCurrentTrack(mixin, tracker);
            } else {
                int min = config.minDelaySeconds * 20;
                int max = Math.max(min + 1, config.maxDelaySeconds * 20);
                autoplayCountdown = min + RANDOM.nextInt(max - min + 1);
                plannedAutoplayPath = null;
            }
        }

        if (!TrackPlaybackService.playing) {
            if (plannedAutoplayPath == null && !plannedAutoplayIsVanilla) {
                planNextAutoplay(client, mode);
            }

            if (autoplayCountdown > 0) {
                autoplayCountdown--;
            } else {
                TrackPlaybackService.playing = executePlannedAutoplay(client, mixin);
                plannedAutoplayPath = null;
                plannedAutoplayIsVanilla = false;
            }
        }
    }

    public static void tickPlaylistAutoplay(IMusicManagerMixin mixin, MusicTracker tracker,
                                            Playlist activePlaylist, ModConfig config, boolean repeatOne) {
        if (tracker.hasPending()) return;

        boolean stillPlaying = WavPlayer.isBusy() || mixin.mdr$isVanillaActive();

        if (TrackPlaybackService.playing && !stillPlaying) {
            TrackPlaybackService.playing = false;
            if (repeatOne) {
                replayCurrentTrack(mixin, tracker);
            } else {
                int min = config.minDelaySeconds * 20;
                int max = Math.max(min + 1, config.maxDelaySeconds * 20);
                autoplayCountdown = min + RANDOM.nextInt(max - min + 1);
                plannedPlaylistEntry = null;
            }
        }

        if (!TrackPlaybackService.playing) {
            if (plannedPlaylistEntry == null) {
                int required = primePlaylistEntryAndGetDelay(activePlaylist, config, 0);
                autoplayCountdown = Math.max(autoplayCountdown, required);
            }

            if (autoplayCountdown > 0) {
                autoplayCountdown--;
            } else if (plannedPlaylistEntry != null) {
                UnifiedTrack unified = plannedPlaylistEntry.toUnifiedTrack();
                if (unified != null) {
                    TrackPlaybackService.playNew(mixin, unified);
                }
                plannedPlaylistEntry = null;
            }
        }
    }

    private static void replayCurrentTrack(IMusicManagerMixin mixin, MusicTracker tracker) {
        UnifiedTrack current = tracker.getCurrentTrack();
        if (current == null) return;
        tracker.setNavigating(true);
        TrackPlaybackService.playNew(mixin, current);
        tracker.setNavigating(false);
    }

    private static int primePlaylistEntryAndGetDelay(Playlist playlist, ModConfig config, int baseDelayTicks) {
        Playlist.PlaylistEntry entry = PlaylistOrderManager.pickNext(playlist, config.trackOrderMode);
        plannedPlaylistEntry = entry;
        if (entry != null && "CUSTOM".equals(entry.type)) {
            Path path = Path.of(entry.value);
            WavPlayer.preload(path);
            if (!TrackVolumeManager.isCached(path)) {
                return Math.max(baseDelayTicks, PLAYLIST_MIN_PRELOAD_TICKS);
            }
        }
        return baseDelayTicks;
    }

    private static void planNextAutoplay(Minecraft client, String mode) {
        List<Path> customTracks = collectAllCustomTracks();
        boolean hasCustom = !customTracks.isEmpty();
        Music situational = client.getSituationalMusic();
        boolean hasVanilla = situational != null;

        if ("CUSTOM".equals(mode)) {
            if (!hasCustom) return;
            Path chosen = pickCustomTrack(customTracks);
            plannedAutoplayPath = chosen;
            plannedAutoplayIsVanilla = false;
            WavPlayer.preload(chosen);
        } else if ("BOTH".equals(mode)) {
            boolean pickCustom = hasCustom && (!hasVanilla || RANDOM.nextBoolean());
            if (pickCustom) {
                Path chosen = pickCustomTrack(customTracks);
                plannedAutoplayPath = chosen;
                plannedAutoplayIsVanilla = false;
                WavPlayer.preload(chosen);
            } else if (hasVanilla) {
                plannedAutoplayIsVanilla = true;
                plannedAutoplayPath = null;
            }
        }
    }

    // Пул для автоплея в режимах CUSTOM/BOTH: папка мода по умолчанию + выбранная игроком
    // папка (если задана) + все их подпапки — то же дерево, что показывает браузер. Раньше
    // здесь была только CustomTrackManager.get().getTracks() (плоский список, одна папка),
    // из-за чего трек, запущенный вручную из выбранной папки или подпапки, после доигрывания
    // "терял" свою папку и автоплей скатывался обратно к папке по умолчанию.
    private static List<Path> collectAllCustomTracks() {
        List<Path> all = new ArrayList<>();
        for (TrackGroup group : FolderTrackLibrary.get().library().getTopLevelGroups()) {
            for (TrackEntry entry : group.collectAllTracks()) {
                all.add(entry.filePath());
            }
        }
        return all;
    }

    private static boolean executePlannedAutoplay(Minecraft client, IMusicManagerMixin mixin) {
        boolean forceFade = StartupSequencer.consumeStartupFadeFlag();
        if (plannedAutoplayPath != null) {
            mixin.mdr$stopAndBlock();
            ModConfig config = ModConfig.get();
            WavPlayer.crossfadeTo(plannedAutoplayPath, forceFade || config.crossfadeEnabled, config.crossfadeDurationSeconds);
            TrackPlaybackService.lastCustomPath = plannedAutoplayPath;
            MusicTracker.get().onTrackStarted(UnifiedTrack.ofCustom(plannedAutoplayPath));
            TrackPlaybackService.showCustomTrackToast(plannedAutoplayPath);
            return true;
        } else if (plannedAutoplayIsVanilla) {
            Music situational = client.getSituationalMusic();
            if (situational != null) {
                WavPlayer.stop();
                mixin.mdr$playVanillaRandom(situational);
                return true;
            }
        }
        return false;
    }

    private static Path pickCustomTrack(List<Path> tracks) {
        return TrackOrderManager.pickNext(tracks, TrackPlaybackService.lastCustomPath, ModConfig.get().trackOrderMode);
    }

    public static void reset() {
        autoplayCountdown = 0;
        plannedAutoplayPath = null;
        plannedAutoplayIsVanilla = false;
        plannedPlaylistEntry = null;
        TrackOrderManager.reset();
        PlaylistOrderManager.reset();
    }
}