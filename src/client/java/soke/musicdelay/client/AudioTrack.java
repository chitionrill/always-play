package soke.musicdelay.client;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Path;

public class AudioTrack {

    private final AudioInputStream stream;
    private final float sampleRate;
    private final int channels;
    private volatile boolean finished = false;

    private AudioTrack(AudioInputStream stream, float sampleRate, int channels) {
        this.stream = stream;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    public static AudioTrack open(Path file) throws Exception {
        AudioInputStream raw = AudioSystem.getAudioInputStream(file.toFile());
        AudioFormat srcFormat = raw.getFormat();

        AudioInputStream converted;
        if (srcFormat.getEncoding() == AudioFormat.Encoding.PCM_SIGNED && srcFormat.getSampleSizeInBits() == 16) {
            converted = raw;
        } else {
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    srcFormat.getSampleRate(),
                    16,
                    srcFormat.getChannels(),
                    srcFormat.getChannels() * 2,
                    srcFormat.getSampleRate(),
                    false
            );
            if (!AudioSystem.isConversionSupported(pcmFormat, srcFormat)) {
                throw new UnsupportedAudioFileException("Cannot decode " + file + " (" + srcFormat + ") to PCM16");
            }
            converted = AudioSystem.getAudioInputStream(pcmFormat, raw);
        }

        AudioFormat finalFormat = converted.getFormat();
        return new AudioTrack(converted, finalFormat.getSampleRate(), finalFormat.getChannels());
    }

    public float getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    private int readCallCount = 0;

    public int read(byte[] buffer) {
        try {
            int frameBytes = channels * 2;
            int usableLength = buffer.length - (buffer.length % frameBytes);
            int read;
            do {
                read = stream.read(buffer, 0, usableLength);
            } while (read == 0);

            if (read < 0) {
                finished = true;
                return -1;
            }
            return read;
        } catch (IOException e) {
            finished = true;
            return -1;
        }
    }

    public boolean isFinished() {
        return finished;
    }

    public void close() {
        try { stream.close(); } catch (IOException ignored) {}
    }
}