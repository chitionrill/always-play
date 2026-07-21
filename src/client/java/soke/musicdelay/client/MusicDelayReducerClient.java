package soke.musicdelay.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundSource;
import soke.musicdelay.ModConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

public class MusicDelayReducerClient implements ClientModInitializer {

	private static final Random RANDOM = new Random();
	private static boolean somethingPlaying = false;
	private static int autoplayCountdown = 0;
	private static int folderRefreshCountdown = 0;
	private static Path lastCustomPath = null;

	private static Path plannedAutoplayPath = null;
	private static boolean plannedAutoplayIsVanilla = false;

	public static volatile boolean startupBlocking = true;
	private static boolean startupInitialized = false;
	private static int startupCountdown = 0;
	private static boolean startupHandled = false;
	private static boolean pendingStartupFade = false;

	private static boolean vanillaStartupFadePending = false;
	private static long vanillaStartupFadeStartNanos = 0;

	private static int volumeUpHeldTicks = 0;
	private static int volumeDownHeldTicks = 0;
	private static final int VOLUME_INITIAL_DELAY_TICKS = 8;
	private static final int VOLUME_REPEAT_INTERVAL_TICKS = 2;

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
			handleVolumeKeys(client);

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
			ModConfig config = ModConfig.get();
			String mode = config.playbackMode;
			int skipDelayTicks = config.skipDelaySeconds * 20;
			Playlist activePlaylist = PlaylistManager.getActivePlaylist();
			boolean playlistMode = activePlaylist != null && !activePlaylist.entries.isEmpty();

			if (!startupHandled) {
				if (!startupInitialized) {
					startupInitialized = true;
					startupCountdown = config.startupFadeEnabled
							? Math.max(1, config.startupDelaySeconds) * 20
							: 0;
					pendingStartupFade = config.startupFadeEnabled;
				}

				if (startupCountdown > 0) {
					startupCountdown--;
					mixin.mdr$stopAndBlock();
					WavPlayer.stop();
					return;
				}

				startupHandled = true;
				startupBlocking = false;

				if (!playlistMode && "VANILLA".equals(mode)) {
					mixin.mdr$unblock(1);
				}
				vanillaStartupFadePending = pendingStartupFade;
			}

			if (vanillaStartupFadePending) {
				if (mixin.mdr$isVanillaActive()) {
					if (vanillaStartupFadeStartNanos == 0) {
						vanillaStartupFadeStartNanos = System.nanoTime();
					}
					double durationNanos = Math.max(0.1, config.crossfadeDurationSeconds) * 1_000_000_000L;
					double progress = Math.min(1.0, (System.nanoTime() - vanillaStartupFadeStartNanos) / durationNanos);
					float sliderVolume = client.options.getSoundSourceVolume(SoundSource.MUSIC);
					mixin.mdr$setGain((float) (sliderVolume * progress));
					if (progress >= 1.0) {
						vanillaStartupFadePending = false;
						vanillaStartupFadeStartNanos = 0;
					}
				}
			}

			// --- Переключение вперёд/назад работает одинаково и в обычном режиме, и в плейлисте ---
			if (ModKeybindings.skipForward.consumeClick()) {
				tracker.clearPending();
				if (!"CUSTOM".equals(mode) || playlistMode) mixin.mdr$stopAndBlock();

				if (tracker.canGoForward()) {
					UnifiedTrack next = tracker.getNextTrack();
					if (skipDelayTicks <= 0) {
						playHistoryTrack(mixin, next);
					} else {
						WavPlayer.stop();
						if (next.type == UnifiedTrack.Type.CUSTOM) {
							WavPlayer.preload(next.customPath);
						}
						tracker.setPending(next, skipDelayTicks);
					}
				} else if (playlistMode) {
					WavPlayer.stop();
					somethingPlaying = false;
					autoplayCountdown = skipDelayTicks;
				} else if ("VANILLA".equals(mode)) {
					mixin.mdr$unblock(skipDelayTicks);
				} else {
					WavPlayer.stop();
					somethingPlaying = false;
					autoplayCountdown = skipDelayTicks;
					plannedAutoplayPath = null;
				}
			}

			if (ModKeybindings.skipBackward.consumeClick() && tracker.canGoBack()) {
				tracker.clearPending();
				if (!"CUSTOM".equals(mode) || playlistMode) mixin.mdr$stopAndBlock();
				UnifiedTrack previous = tracker.getPreviousTrack();
				if (skipDelayTicks <= 0) {
					playHistoryTrack(mixin, previous);
				} else {
					WavPlayer.stop();
					if (previous.type == UnifiedTrack.Type.CUSTOM) {
						WavPlayer.preload(previous.customPath);
					}
					tracker.setPending(previous, skipDelayTicks);
				}
			}

			if (tracker.hasPending() && tracker.tickPending()) {
				UnifiedTrack pending = tracker.consumePending();
				playHistoryTrack(mixin, pending);
			}

			if (playlistMode) {
				if (!tracker.hasPending()) {
					boolean stillPlaying = WavPlayer.isBusy() || mixin.mdr$isVanillaActive();

					if (somethingPlaying && !stillPlaying) {
						somethingPlaying = false;
						int min = config.minDelaySeconds * 20;
						int max = Math.max(min + 1, config.maxDelaySeconds * 20);
						autoplayCountdown = min + RANDOM.nextInt(max - min + 1);
					}

					if (!somethingPlaying) {
						if (autoplayCountdown > 0) {
							autoplayCountdown--;
						} else {
							tryPlayNextFromPlaylist(mixin, activePlaylist, config);
						}
					}
				}
				return;
			}

			if (!"VANILLA".equals(mode) && !tracker.hasPending()) {
				boolean stillPlaying = WavPlayer.isBusy() || mixin.mdr$isVanillaActive();

				if (somethingPlaying && !stillPlaying) {
					somethingPlaying = false;
					int min = config.minDelaySeconds * 20;
					int max = Math.max(min + 1, config.maxDelaySeconds * 20);
					autoplayCountdown = min + RANDOM.nextInt(max - min + 1);
					plannedAutoplayPath = null;
				}

				if (!somethingPlaying) {
					if (plannedAutoplayPath == null && !plannedAutoplayIsVanilla) {
						planNextAutoplay(client, mode);
					}

					if (autoplayCountdown > 0) {
						autoplayCountdown--;
					} else {
						somethingPlaying = executePlannedAutoplay(client, mixin, mode);
						plannedAutoplayPath = null;
						plannedAutoplayIsVanilla = false;
					}
				}
			}
		});
	}

	private static void tryPlayNextFromPlaylist(IMusicManagerMixin mixin, Playlist playlist, ModConfig config) {
		int attempts = 0;
		int maxAttempts = playlist.entries.size() + 1;
		while (attempts < maxAttempts) {
			Playlist.PlaylistEntry entry = PlaylistOrderManager.pickNext(playlist, config.trackOrderMode);
			if (entry == null) return;
			UnifiedTrack unified = entry.toUnifiedTrack();
			if (unified != null) {
				playNewTrack(mixin, unified);
				return;
			}
			attempts++;
		}
	}

	// Играет НОВЫЙ (ещё не бывший в истории) трек и записывает его в историю —
	// используется и обычным автовоспроизведением, и плейлистом
	private static void playNewTrack(IMusicManagerMixin mixin, UnifiedTrack track) {
		mixin.mdr$stopAndBlock();
		if (track.type == UnifiedTrack.Type.CUSTOM) {
			ModConfig config = ModConfig.get();
			WavPlayer.crossfadeTo(track.customPath, config.crossfadeEnabled, config.crossfadeDurationSeconds);
			lastCustomPath = track.customPath;
			showCustomTrackToast(track.customPath);
		} else {
			WavPlayer.stop();
			mixin.mdr$playFixed(track.vanillaSound);
		}
		MusicTracker.get().onTrackStarted(track);
		somethingPlaying = true;
	}

	// Вызывается при клике "▶" на конкретном треке внутри просмотра плейлиста
	public static void playPlaylistEntryDirect(Playlist playlist, Playlist.PlaylistEntry entry) {
		UnifiedTrack unified = entry.toUnifiedTrack();
		if (unified == null) return;

		PlaylistManager.setActivePlaylist(playlist.id);
		ModConfig.get().activePlaylistId = playlist.id;
		ModConfig.get().save();

		MusicTracker.get().clearPending();
		autoplayCountdown = 0;

		Minecraft client = Minecraft.getInstance();
		IMusicManagerMixin mixin = (IMusicManagerMixin) client.getMusicManager();
		playNewTrack(mixin, unified);
	}

	private static void handleVolumeKeys(Minecraft client) {
		if (ModKeybindings.volumeUp.isDown()) {
			volumeUpHeldTicks++;
			if (shouldStep(volumeUpHeldTicks)) {
				adjustMusicVolume(client, 0.05);
			}
		} else {
			volumeUpHeldTicks = 0;
		}

		if (ModKeybindings.volumeDown.isDown()) {
			volumeDownHeldTicks++;
			if (shouldStep(volumeDownHeldTicks)) {
				adjustMusicVolume(client, -0.05);
			}
		} else {
			volumeDownHeldTicks = 0;
		}
	}

	private static boolean shouldStep(int heldTicks) {
		if (heldTicks == 1) return true;
		return heldTicks > VOLUME_INITIAL_DELAY_TICKS
				&& (heldTicks - VOLUME_INITIAL_DELAY_TICKS) % VOLUME_REPEAT_INTERVAL_TICKS == 0;
	}

	private static void adjustMusicVolume(Minecraft client, double delta) {
		OptionInstance<Double> option = client.options.getSoundSourceOptionInstance(SoundSource.MUSIC);
		double current = option.get();
		double updated = Math.max(0.0, Math.min(1.0, current + delta));
		option.set(updated);
		client.options.save();
	}

	private static void showCustomTrackToast(Path path) {
		if (!Minecraft.getInstance().options.musicToast().get().renderToast()) return;
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		if (dot > 0) name = name.substring(0, dot);
		CustomTrackToast.showTrack(name);
	}

	private static void playHistoryTrack(IMusicManagerMixin mixin, UnifiedTrack track) {
		if (track.type == UnifiedTrack.Type.VANILLA) {
			mixin.mdr$stopAndBlock();
		}
		boolean forceFade = consumeStartupFadeFlag();
		if (track.type == UnifiedTrack.Type.CUSTOM) {
			ModConfig config = ModConfig.get();
			WavPlayer.crossfadeTo(track.customPath, forceFade || config.crossfadeEnabled, config.crossfadeDurationSeconds);
			lastCustomPath = track.customPath;
			showCustomTrackToast(track.customPath);
		} else {
			WavPlayer.stop();
			mixin.mdr$playFixed(track.vanillaSound);
		}
		somethingPlaying = true;
	}

	public static void playFromBrowser(BrowsableTrack track) {
		Minecraft client = Minecraft.getInstance();
		MusicManager manager = client.getMusicManager();
		IMusicManagerMixin mixin = (IMusicManagerMixin) manager;
		MusicTracker tracker = MusicTracker.get();

		PlaylistManager.setActivePlaylist(null);
		ModConfig.get().activePlaylistId = null;
		ModConfig.get().save();

		tracker.clearPending();
		somethingPlaying = true;
		autoplayCountdown = 0;
		plannedAutoplayPath = null;
		plannedAutoplayIsVanilla = false;

		mixin.mdr$stopAndBlock();
		UnifiedTrack unified = track.toUnifiedTrack();

		if (unified.type == UnifiedTrack.Type.CUSTOM) {
			ModConfig config = ModConfig.get();
			WavPlayer.crossfadeTo(unified.customPath, config.crossfadeEnabled, config.crossfadeDurationSeconds);
			lastCustomPath = unified.customPath;
			tracker.onTrackStarted(unified);
			showCustomTrackToast(unified.customPath);
		} else {
			WavPlayer.stop();
			mixin.mdr$playFixed(unified.vanillaSound);
			tracker.onTrackStarted(unified);
		}
	}

	private static void planNextAutoplay(Minecraft client, String mode) {
		List<Path> customTracks = CustomTrackManager.get().getTracks();
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

	private static boolean executePlannedAutoplay(Minecraft client, IMusicManagerMixin mixin, String mode) {
		boolean forceFade = consumeStartupFadeFlag();
		if (plannedAutoplayPath != null) {
			mixin.mdr$stopAndBlock();
			ModConfig config = ModConfig.get();
			WavPlayer.crossfadeTo(plannedAutoplayPath, forceFade || config.crossfadeEnabled, config.crossfadeDurationSeconds);
			lastCustomPath = plannedAutoplayPath;
			MusicTracker.get().onTrackStarted(UnifiedTrack.ofCustom(plannedAutoplayPath));
			showCustomTrackToast(plannedAutoplayPath);
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

	private static boolean consumeStartupFadeFlag() {
		if (pendingStartupFade) {
			pendingStartupFade = false;
			return true;
		}
		return false;
	}

	private static Path pickCustomTrack(List<Path> tracks) {
		return TrackOrderManager.pickNext(tracks, lastCustomPath, ModConfig.get().trackOrderMode);
	}

	public static void resetPlaybackState() {
		WavPlayer.stop();
		MusicManager manager = Minecraft.getInstance().getMusicManager();
		IMusicManagerMixin mixin = (IMusicManagerMixin) manager;
		mixin.mdr$stopAndBlock();
		MusicTracker.get().clearPending();
		somethingPlaying = false;
		autoplayCountdown = 0;
		plannedAutoplayPath = null;
		plannedAutoplayIsVanilla = false;
		TrackOrderManager.reset();
		PlaylistOrderManager.reset();

		Playlist active = PlaylistManager.getActivePlaylist();
		if (active == null && "VANILLA".equals(ModConfig.get().playbackMode)) {
			mixin.mdr$unblock(1);
		}
	}

	public static void restartForWorldJoin() {
		startupInitialized = false;
		startupHandled = false;
		startupBlocking = true;
		pendingStartupFade = false;
		vanillaStartupFadePending = false;
		vanillaStartupFadeStartNanos = 0;
		resetPlaybackState();
	}
}