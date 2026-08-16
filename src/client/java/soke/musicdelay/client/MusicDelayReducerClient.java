package soke.musicdelay.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.SoundSource;
import soke.musicdelay.ModConfig;
import soke.musicdelay.client.gui.CustomTrackToast;
import soke.musicdelay.client.gui.MusicBrowserScreen;
import soke.musicdelay.client.playback.StartupSequencer;
import soke.musicdelay.client.playback.TrackPlaybackService;
import soke.musicdelay.client.playback.VolumeKeyController;
import soke.musicdelay.client.playback.PlaybackScheduler;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

public class MusicDelayReducerClient implements ClientModInitializer {

	private static boolean paused = false; // новое
	private static boolean repeatOne = false; // новое
	private static int folderRefreshCountdown = 0;

	@Override
	public void onInitializeClient() {
		ModKeybindings.register();
		CustomTrackManager.get().refresh();
		PlaylistManager.setActivePlaylist(ModConfig.get().activePlaylistId);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			// Обновляем список ванильных треков (в т.ч. пластинок) при каждом заходе в мир —
			// реестр пластинок доступен только когда мир загружен, без этого он остаётся пустым/устаревшим
			VanillaTrackRegistry.refresh();
			if (ModConfig.get().worldRestartEnabled) {
				restartForWorldJoin();
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			WavPlayer.tickVolumeSync();
			VolumeKeyController.tick(client);

			if (ModKeybindings.openMusicBrowser.consumeClick() && client.level != null && client.gui.screen() == null) {
				client.gui.setScreen(new MusicBrowserScreen(null));
			}

			if (--folderRefreshCountdown <= 0) {
				CustomTrackManager.get().refresh();
				folderRefreshCountdown = 100;
			}

			MusicTracker tracker = MusicTracker.get();
			MusicManager manager = client.getMusicManager();
			IMusicManagerMixin mixin = (IMusicManagerMixin) manager;

			// --- новое: пауза/возобновление ---
			if (ModKeybindings.pauseResume.consumeClick()) {
				paused = !paused;
				if (paused) {
					WavPlayer.pause();
					mixin.mdr$setGain(0f);
				} else {
					WavPlayer.resume();
					mixin.mdr$setGain(client.options.getSoundSourceVolume(SoundSource.MUSIC));
				}
			}
			if (paused) {
				// Замораживаем весь остальной тик: не даём плейлисту/автовоспроизведению
				// среагировать на "завершение" трека, пока мы стоим на паузе
				return;
			}
			// --- конец нового ---

			// --- новое: повтор одного трека ---
			if (ModKeybindings.repeatOne.consumeClick()) {
				repeatOne = !repeatOne;
				CustomTrackToast.showTrack(net.minecraft.network.chat.Component.translatable(
						repeatOne ? "music-delay-reducer.toast.repeat_on" : "music-delay-reducer.toast.repeat_off"));
			}
			// --- конец нового ---

			ModConfig config = ModConfig.get();
			String mode = config.playbackMode;
			int skipDelayTicks = config.skipDelaySeconds * 20;
			Playlist activePlaylist = PlaylistManager.getActivePlaylist();
			boolean playlistMode = activePlaylist != null && !activePlaylist.entries.isEmpty();

			if (StartupSequencer.tick(mixin, config, playlistMode, mode)) {
				return;
			}
			StartupSequencer.tickVanillaFade(client, mixin, config);

			// --- Переключение вперёд/назад и автоплей теперь в PlaybackScheduler ---
			if (ModKeybindings.skipForward.consumeClick()) {
				PlaybackScheduler.handleSkipForward(mixin, tracker, mode, playlistMode, activePlaylist, config, skipDelayTicks);
			}

			if (ModKeybindings.skipBackward.consumeClick()) {
				PlaybackScheduler.handleSkipBackward(mixin, tracker, mode, playlistMode, skipDelayTicks);
			}

			PlaybackScheduler.tickPending(mixin, tracker);

			if (playlistMode) {
				PlaybackScheduler.tickPlaylistAutoplay(mixin, tracker, activePlaylist, config, repeatOne);
				return;
			}

			if (!"VANILLA".equals(mode)) {
				PlaybackScheduler.tickAutoplay(client, mixin, tracker, config, mode, repeatOne);
			}
		});
	}

	// Вызывается при клике "▶" на конкретном треке внутри просмотра плейлиста
	public static void playPlaylistEntryDirect(Playlist playlist, Playlist.PlaylistEntry entry) {
		UnifiedTrack unified = entry.toUnifiedTrack();
		if (unified == null) return;

		PlaylistManager.setActivePlaylist(playlist.id);

		MusicTracker.get().clearPending();
		PlaybackScheduler.autoplayCountdown = 0;

		Minecraft client = Minecraft.getInstance();
		IMusicManagerMixin mixin = (IMusicManagerMixin) client.getMusicManager();
		TrackPlaybackService.playNew(mixin, unified);
	}

	public static void playFromBrowser(BrowsableTrack track) {
		Minecraft client = Minecraft.getInstance();
		MusicManager manager = client.getMusicManager();
		IMusicManagerMixin mixin = (IMusicManagerMixin) manager;
		MusicTracker tracker = MusicTracker.get();

		PlaylistManager.setActivePlaylist(null);

		tracker.clearPending();
		TrackPlaybackService.playing = true;
		PlaybackScheduler.autoplayCountdown = 0;
		PlaybackScheduler.plannedAutoplayPath = null;
		PlaybackScheduler.plannedAutoplayIsVanilla = false;

		mixin.mdr$stopAndBlock();
		UnifiedTrack unified = track.toUnifiedTrack();

		if (unified.type == UnifiedTrack.Type.CUSTOM) {
			ModConfig config = ModConfig.get();
			WavPlayer.crossfadeTo(unified.customPath, config.crossfadeEnabled, config.crossfadeDurationSeconds);
			TrackPlaybackService.lastCustomPath = unified.customPath;
			tracker.onTrackStarted(unified);
			TrackPlaybackService.showCustomTrackToast(unified.customPath);
		} else {
			WavPlayer.stop();
			mixin.mdr$playFixed(unified.vanillaSound);
			tracker.onTrackStarted(unified);
			TrackPlaybackService.showVanillaTrackToast(unified.vanillaSound);
		}
	}

	public static void resetPlaybackState() {
		WavPlayer.stop();
		MusicManager manager = Minecraft.getInstance().getMusicManager();
		IMusicManagerMixin mixin = (IMusicManagerMixin) manager;
		mixin.mdr$stopAndBlock();
		MusicTracker.get().clearPending();
		TrackPlaybackService.playing = false;
		PlaybackScheduler.reset();

		if (PlaylistManager.getActivePlaylist() == null && "VANILLA".equals(ModConfig.get().playbackMode)) {
			mixin.mdr$unblock(1);
		}
	}

	public static void restartForWorldJoin() {
		StartupSequencer.reset();
		resetPlaybackState();
	}
}