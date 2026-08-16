package soke.musicdelay.client.playback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.network.chat.Component;
import soke.musicdelay.ModConfig;
import soke.musicdelay.client.gui.CustomTrackToast;
import soke.musicdelay.client.IMusicManagerMixin;
import soke.musicdelay.client.MusicTracker;
import soke.musicdelay.client.UnifiedTrack;
import soke.musicdelay.client.VanillaTrackRegistry;
import soke.musicdelay.client.WavPlayer;

import java.nio.file.Path;

// Единая точка "начать играть этот конкретный трек" — вызывается автоплеем,
// плейлистом, историей (skip forward/backward), браузером треков,
// а в будущем (1.5) — событийной системой.
public class TrackPlaybackService {

    public static boolean playing = false;
    public static Path lastCustomPath = null;

    // Играет НОВЫЙ (ещё не бывший в истории) трек и записывает его в историю.
    public static void playNew(IMusicManagerMixin mixin, UnifiedTrack track) {
        mixin.mdr$stopAndBlock();
        if (track.type == UnifiedTrack.Type.CUSTOM) {
            ModConfig config = ModConfig.get();
            WavPlayer.crossfadeTo(track.customPath, config.crossfadeEnabled, config.crossfadeDurationSeconds);
            TrackPlaybackService.lastCustomPath = track.customPath;
            TrackPlaybackService.showCustomTrackToast(track.customPath);
        } else {
            WavPlayer.stop();
            mixin.mdr$playFixed(track.vanillaSound);
            TrackPlaybackService.showVanillaTrackToast(track.vanillaSound);
        }
        MusicTracker.get().onTrackStarted(track);
        playing = true;
    }

    // Играет трек ИЗ истории (skip forward/backward) — не пишет его повторно в историю.
    public static void playHistory(IMusicManagerMixin mixin, UnifiedTrack track) {
        if (track.type == UnifiedTrack.Type.VANILLA) {
            mixin.mdr$stopAndBlock();
        }
        boolean forceFade = StartupSequencer.consumeStartupFadeFlag();
        if (track.type == UnifiedTrack.Type.CUSTOM) {
            ModConfig config = ModConfig.get();
            WavPlayer.crossfadeTo(track.customPath, forceFade || config.crossfadeEnabled, config.crossfadeDurationSeconds);
            TrackPlaybackService.lastCustomPath = track.customPath;
            TrackPlaybackService.showCustomTrackToast(track.customPath);
        } else {
            WavPlayer.stop();
            mixin.mdr$playFixed(track.vanillaSound);
            TrackPlaybackService.showVanillaTrackToast(track.vanillaSound);
        }
        playing = true;
    }

    public static void showCustomTrackToast(Path path) {
        if (!Minecraft.getInstance().options.musicToast().get().renderToast()) return;
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        Minecraft.getInstance().gui.toastManager().hideNowPlayingToast();
        CustomTrackToast.showTrack(Component.literal(name));
    }

    public static void showVanillaTrackToast(Sound sound) {
        if (!Minecraft.getInstance().options.musicToast().get().renderToast()) return;
        Minecraft.getInstance().gui.toastManager().hideNowPlayingToast();
        CustomTrackToast.showTrack(VanillaTrackRegistry.getDisplayNameForLocation(sound.getLocation()));
    }
}