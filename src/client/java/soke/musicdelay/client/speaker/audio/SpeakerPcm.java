package soke.musicdelay.client.speaker.audio;

/** PCM16 little-endian spatial source: fold stereo to mono before positioning it. */
public final class SpeakerPcm {
    private SpeakerPcm() { }

    public record Levels(float gain, float pan) {
        public Levels {
            if (!Float.isFinite(gain) || gain < 0 || gain > 1
                    || !Float.isFinite(pan) || pan < -1 || pan > 1) {
                throw new IllegalArgumentException("Invalid speaker levels");
            }
        }
        public static final Levels SILENT = new Levels(0, 0);
    }

    /** The range falls with speaker volume; occlusion/reflections are a later layer. */
    public static Levels spatial(double distance, double right, float volume,
                                 float maxRadius, float master, float records, boolean carriedByListener) {
        if (!Double.isFinite(distance) || distance < 0 || !Double.isFinite(right)
                || !Float.isFinite(maxRadius) || maxRadius <= 0) {
            throw new IllegalArgumentException("Invalid source geometry");
        }
        unit(volume); unit(master); unit(records);
        double radius = maxRadius * volume;
        float gain = radius <= 0 || distance >= radius ? 0
                : (float) (volume * master * records * Math.pow(1 - distance / radius, 2));
        return new Levels(gain, carriedByListener ? 0 : (float) Math.clamp(right, -1, 1));
    }

    public static int mix(byte[] input, int count, int channels, byte[] output, Levels from, Levels to) {
        if ((channels != 1 && channels != 2) || count < 0 || count > input.length
                || count % (channels * 2) != 0 || output.length < count / (channels * 2) * 4) {
            throw new IllegalArgumentException("Invalid PCM block");
        }
        int frames = count / (channels * 2);
        for (int i = 0; i < frames; i++) {
            float t = (i + 1f) / frames;
            double gain = from.gain + (to.gain - from.gain) * t;
            double pan = from.pan + (to.pan - from.pan) * t;
            double angle = (pan + 1) * Math.PI / 4;
            int offset = i * channels * 2;
            double sample = sample(input, offset);
            if (channels == 2) sample = (sample + sample(input, offset + 2)) / 2;
            put(output, i * 4, (int) Math.round(sample * gain * Math.cos(angle)));
            put(output, i * 4 + 2, (int) Math.round(sample * gain * Math.sin(angle)));
        }
        return frames * 4;
    }

    private static void unit(float value) {
        if (!Float.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Invalid volume");
    }
    private static int sample(byte[] b, int o) { return (short) ((b[o] & 255) | (b[o + 1] << 8)); }
    private static void put(byte[] b, int o, int v) {
        v = Math.clamp(v, -32768, 32767); b[o] = (byte) v; b[o + 1] = (byte) (v >> 8);
    }
}
