package soke.musicdelay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

public class PreviewPlayer {

    private static SoundInstance currentVanillaPreview = null;
    private static boolean customPreviewActive = false;

    public static void play(BrowsableTrack track) {
        stop();
        if (track.kind == BrowsableTrack.Kind.CUSTOM) {
            customPreviewActive = true;
            PreviewAudioEngine.play(track.customPath);
        } else {
            Sound sound = track.vanillaSound;
            SoundInstance instance = new FixedSoundInstance(
                    sound,
                    Identifier.fromNamespaceAndPath("music-delay-reducer", "preview"),
                    SoundSource.MUSIC,
                    SoundInstance.createUnseededRandom()
            );
            currentVanillaPreview = instance;
            Minecraft.getInstance().getSoundManager().play(instance);
        }
    }

    public static void stop() {
        if (currentVanillaPreview != null) {
            Minecraft.getInstance().getSoundManager().stop(currentVanillaPreview);
            currentVanillaPreview = null;
        }
        if (customPreviewActive) {
            PreviewAudioEngine.stop();
            customPreviewActive = false;
        }
    }
}