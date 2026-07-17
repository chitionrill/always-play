package soke.musicdelay.client;

import javax.sound.sampled.*;
import java.nio.file.Path;

public class TrackVolumeAnalyzer {

    private static final double TARGET_DBFS = -18.0;
    private static final double MIN_OFFSET_DB = -30.0;
    private static final double MAX_OFFSET_DB = 12.0;

    public static double analyzeGainOffsetDb(Path file) {
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

            byte[] buffer = new byte[4096];
            long sumSquares = 0;
            long sampleCount = 0;
            int read;

            while ((read = audioIn.read(buffer)) != -1) {
                for (int i = 0; i + 1 < read; i += 2) {
                    short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                    sumSquares += (long) sample * sample;
                    sampleCount++;
                }
            }
            audioIn.close();

            if (sampleCount == 0) return 0.0;

            double meanSquare = (double) sumSquares / sampleCount;
            double rms = Math.sqrt(meanSquare) / 32768.0;
            if (rms <= 0.00001) return 0.0;

            double measuredDbfs = 20.0 * Math.log10(rms);
            double offset = TARGET_DBFS - measuredDbfs;
            return Math.max(MIN_OFFSET_DB, Math.min(MAX_OFFSET_DB, offset));
        } catch (Exception e) {
            e.printStackTrace();
            return 0.0;
        }
    }
}