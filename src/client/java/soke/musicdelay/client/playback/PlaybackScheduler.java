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
            boolean isNew = tracker.isPendingNew();
            UnifiedTrack pending = tracker.consumePending();
            if (isNew) {
                TrackPlaybackService.playNew(mixin, pending);
            } else {
                TrackPlaybackService.playHistory(mixin, pending);
            }
        }
    }

    public static void tickAutoplay(Minecraft client, IMusicManagerMixin mixin, MusicTracker tracker,
                                    ModConfig config, String mode, boolean repeatOne) {
        // Раньше эта проверка тут отсутствовала (была только в tickPlaylistAutoplay) — не было
        // нужды, потому что crossfadeTo() внутри executePlannedAutoplay блокировался до готовности
        // кэша. Теперь crossfadeTo() неблокирующий и может выставить pending сам — если не
        // остановиться здесь, tickAutoplay продолжит планировать новые треки поверх ожидающего.
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
        List<Path> customTracks = QueuePlanner.collectAllCustomTracks();
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

    private static boolean executePlannedAutoplay(Minecraft client, IMusicManagerMixin mixin) {
        if (plannedAutoplayPath != null) {
            mixin.mdr$stopAndBlock();
            // peek, а не consume — если crossfadeTo не готов, флаг должен дожить до реальной
            // успешной попытки (тот же приём, что и в TrackPlaybackService.playHistory)
            boolean forceFade = StartupSequencer.peekStartupFadeFlag();
            ModConfig config = ModConfig.get();
            boolean started = WavPlayer.crossfadeTo(plannedAutoplayPath, forceFade || config.crossfadeEnabled, config.crossfadeDurationSeconds);
            if (!started) {
                // Кэш не успел подготовиться — не считаем автоплей выполненным, отдаём треку
                // короткую догрузку через тот же pending-механизм, что у ручного скипа.
                // plannedAutoplayPath ниже в tickAutoplay всё равно сбросится в null — это ок,
                // трек уже сохранён внутри pending и будет доигран через tickPending().
                MusicTracker.get().setPendingNew(UnifiedTrack.ofCustom(plannedAutoplayPath), TrackPlaybackService.CACHE_WAIT_TICKS);
                return false;
            }
            StartupSequencer.consumeStartupFadeFlag();
            TrackPlaybackService.lastCustomPath = plannedAutoplayPath;
            MusicTracker.get().onTrackStarted(UnifiedTrack.ofCustom(plannedAutoplayPath));
            TrackPlaybackService.showCustomTrackToast(plannedAutoplayPath);
            return true;
        } else if (plannedAutoplayIsVanilla) {
            StartupSequencer.consumeStartupFadeFlag(); // как и раньше — консьюмится безусловно, хоть здесь и не используется
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

    // Единая точка входа для prefetch-системы — безопасно звать каждый тик, реально
    // пересчитывает окно только когда изменилось что-то существенное для предсказания
    // (текущий трек, режим, плейлист, наличие pending-догрузки). Это даёт реакцию на все
    // события разом (skip, обычное докручивание, смена плейлиста/режима), не требуя
    // отдельного вызова refresh() из каждого места, где меняется трек — и не пересканирует
    // на пустом месте folder-based плейлист каждый тик (PlaylistOrderManager.peekNext дергает
    // Playlist.resolveEntries(), что при неизменном ключе просто не будет вызвано лишний раз).
    private static Object lastPreloadTriggerKey = null;

    public static void tickPreload(MusicTracker tracker, boolean playlistMode, Playlist activePlaylist, String mode) {
        UnifiedTrack current = tracker.getCurrentTrack();
        Path currentCustomPath = (current != null && current.type == UnifiedTrack.Type.CUSTOM) ? current.customPath : null;

        List<Object> key = new ArrayList<>();
        key.add(currentCustomPath);
        key.add(playlistMode);
        key.add(activePlaylist != null ? activePlaylist.id : null);
        key.add(mode);
        key.add(tracker.hasPending());

        if (key.equals(lastPreloadTriggerKey)) return;
        lastPreloadTriggerKey = key;
        TrackPreloadManager.refresh(tracker, playlistMode, activePlaylist, mode);
    }

    public static void reset() {
        autoplayCountdown = 0;
        plannedAutoplayPath = null;
        plannedAutoplayIsVanilla = false;
        plannedPlaylistEntry = null;
        TrackOrderManager.reset();
        PlaylistOrderManager.reset();
        TrackPreloadManager.reset();
        lastPreloadTriggerKey = null;
    }
}