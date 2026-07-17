package soke.musicdelay.client;

import javax.sound.sampled.*;
import java.nio.file.Path;

public class PreviewAudioEngine {

    private static Clip currentClip;

    public static void play(Path file) {
        stop();
        try {
            AudioInputStream rawStream = AudioSystem.getAudioInputStream(file.toFile());
            AudioFormat sourceFormat = rawStream.getFormat();
            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    sourceFormat.getChannels(),
                    sourceFormat.getChannels() * 2,
                    sourceFormat.getSampleRate(),
                    false
            );
            AudioInputStream audioIn = AudioSystem.isConversionSupported(targetFormat, sourceFormat)
                    ? AudioSystem.getAudioInputStream(targetFormat, rawStream)
                    : rawStream;

            Clip clip = AudioSystem.getClip();
            clip.open(audioIn);
            currentClip = clip;
            clip.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void stop() {
        if (currentClip != null) {
            currentClip.stop();
            currentClip.close();
            currentClip = null;
        }
    }
}