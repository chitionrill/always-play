package soke.musicdelay.client;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public class AudioTrack {
    private final AudioInputStream stream;
    private final float sampleRate;
    private final int channels;
    private volatile boolean finished;

    private AudioTrack(AudioInputStream stream) {
        this.stream = stream;
        this.sampleRate = stream.getFormat().getSampleRate();
        this.channels = stream.getFormat().getChannels();
    }

    public static AudioTrack open(Path file) throws Exception {
        return decode(AudioSystem.getAudioInputStream(file.toFile()));
    }

    // Ownership of input passes to AudioTrack, including on failure.
    public static AudioTrack open(InputStream input) throws Exception {
        InputStream buffered = input.markSupported()
                ? input
                : new BufferedInputStream(input);
        try {
            return decode(AudioSystem.getAudioInputStream(buffered));
        } catch (Exception e) {
            try { buffered.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    private static AudioTrack decode(AudioInputStream raw) throws Exception {
        try {
            AudioFormat source = raw.getFormat();
            if (source.getChannels() < 1 || source.getChannels() > 2) {
                throw new UnsupportedAudioFileException("Only mono and stereo are supported");
            }
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    source.getSampleRate(), 16, source.getChannels(),
                    source.getChannels() * 2, source.getSampleRate(), false);
            if (target.matches(source)) return new AudioTrack(raw);
            if (!AudioSystem.isConversionSupported(target, source)) {
                throw new UnsupportedAudioFileException("Cannot decode to PCM16: " + source);
            }
            return new AudioTrack(AudioSystem.getAudioInputStream(target, raw));
        } catch (Exception e) {
            try { raw.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    public float getSampleRate() { return sampleRate; }
    public int getChannels() { return channels; }
    public boolean isFinished() { return finished; }

    public int read(byte[] buffer) {
        try {
            int frameBytes = channels * 2;
            int length = buffer.length - buffer.length % frameBytes;
            if (length == 0) throw new IllegalArgumentException("Buffer is smaller than one frame");
            int count;
            do { count = stream.read(buffer, 0, length); } while (count == 0);
            if (count < 0) finished = true;
            return count;
        } catch (IOException e) {
            finished = true;
            return -1;
        }
    }

    public void close() {
        try { stream.close(); } catch (IOException ignored) {}
    }
}