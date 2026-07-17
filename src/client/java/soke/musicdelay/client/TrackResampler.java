package soke.musicdelay.client;

import java.util.Arrays;

public class TrackResampler {
    private final AudioTrack track;
    private final int channels;
    private final double ratio; // частота_трека / частота_движка

    private float[] bufL = new float[4096];
    private float[] bufR = new float[4096];
    private int bufCount = 0;
    private double cursor = 0.0;

    private final byte[] readTemp;

    public TrackResampler(AudioTrack track, int outputSampleRate) {
        this.track = track;
        this.channels = track.getChannels();
        this.ratio = track.getSampleRate() / (double) outputSampleRate;
        int frameBytes = channels * 2;
        this.readTemp = new byte[2048 * frameBytes];
    }

    public boolean isFinished() {
        return track.isFinished() && (int) Math.floor(cursor) + 1 >= bufCount;
    }

    private void ensureAvailable(int neededSamples) {
        while (bufCount < neededSamples && !track.isFinished()) {
            int read = track.read(readTemp);
            if (read <= 0) break;
            int frameBytes = channels * 2;
            int frames = read / frameBytes;
            ensureCapacity(bufCount + frames);
            for (int f = 0; f < frames; f++) {
                int base = f * frameBytes;
                short l = (short) ((readTemp[base + 1] << 8) | (readTemp[base] & 0xFF));
                float lf = l / 32768f;
                float rf;
                if (channels >= 2) {
                    short r = (short) ((readTemp[base + 3] << 8) | (readTemp[base + 2] & 0xFF));
                    rf = r / 32768f;
                } else {
                    rf = lf;
                }
                bufL[bufCount] = lf;
                bufR[bufCount] = rf;
                bufCount++;
            }
        }
    }

    private void ensureCapacity(int needed) {
        if (needed <= bufL.length) return;
        int newSize = Math.max(needed, bufL.length * 2);
        bufL = Arrays.copyOf(bufL, newSize);
        bufR = Arrays.copyOf(bufR, newSize);
    }

    // Заполняет outInterleaved (L,R,L,R...) заданным числом кадров в частоте движка,
    // самостоятельно пересчитывая (интерполируя) из родной частоты трека
    public void fillBlock(float[] outInterleaved, int frames) {
        for (int i = 0; i < frames; i++) {
            int i0 = (int) Math.floor(cursor);
            ensureAvailable(i0 + 2);

            float l0 = i0 < bufCount ? bufL[i0] : 0f;
            float r0 = i0 < bufCount ? bufR[i0] : 0f;
            float l1 = (i0 + 1) < bufCount ? bufL[i0 + 1] : l0;
            float r1 = (i0 + 1) < bufCount ? bufR[i0 + 1] : r0;
            double frac = cursor - i0;

            outInterleaved[i * 2] = (float) (l0 + (l1 - l0) * frac);
            outInterleaved[i * 2 + 1] = (float) (r0 + (r1 - r0) * frac);

            cursor += ratio;
        }

        int consumed = (int) Math.floor(cursor);
        if (consumed > 0) {
            int remaining = bufCount - consumed;
            if (remaining > 0) {
                System.arraycopy(bufL, consumed, bufL, 0, remaining);
                System.arraycopy(bufR, consumed, bufR, 0, remaining);
            }
            bufCount = Math.max(0, remaining);
            cursor -= consumed;
        }
    }
}