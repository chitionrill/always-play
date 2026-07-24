package soke.musicdelay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import soke.musicdelay.ModConfig;

import javax.sound.sampled.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WavPlayer {

    private static final int OUTPUT_SAMPLE_RATE = 44100;
    private static final AudioFormat OUTPUT_FORMAT = new AudioFormat(OUTPUT_SAMPLE_RATE, 16, 2, true, false);
    private static final int BLOCK_FRAMES = 1024;

    private static SourceDataLine line;
    private static Thread engineThread;
    private static volatile boolean running = false;
    private static volatile float cachedMusicVolume = 1.0f;

    private static class TrackState {
        TrackResampler resampler;
        double offsetDb;
        long fadeStartNanos;
        long fadeDurationNanos;
        boolean fadingIn;
    }

    private static TrackState current;
    private static final List<TrackState> outgoing = new ArrayList<>();
    private static final Object lock = new Object();

    private static final ExecutorService PRELOAD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mdr-audio-preload");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Path, java.util.concurrent.CompletableFuture<AudioTrack>> preloadedTracks = new ConcurrentHashMap<>();

    private static void init() {
        if (running) return;
        try {
            line = AudioSystem.getSourceDataLine(OUTPUT_FORMAT);
            line.open(OUTPUT_FORMAT, BLOCK_FRAMES * 4 * 4);
            line.start();
        } catch (LineUnavailableException e) {
            e.printStackTrace();
            return;
        }
        running = true;
        engineThread = new Thread(WavPlayer::runEngineLoop, "mdr-audio-engine");
        engineThread.setDaemon(true);
        engineThread.start();
    }

    private static void runEngineLoop() {
        float[] blockBuf = new float[BLOCK_FRAMES * 2];
        float[] accum = new float[BLOCK_FRAMES * 2];
        byte[] out = new byte[BLOCK_FRAMES * 4];

        while (running) {
            TrackState currentLocal;
            List<TrackState> sources;
            synchronized (lock) {
                currentLocal = current;
                sources = new ArrayList<>(outgoing);
            }

            Arrays.fill(accum, 0f);
            List<TrackState> toRemove = new ArrayList<>();

            if (currentLocal != null && !currentLocal.resampler.isFinished()) {
                currentLocal.resampler.fillBlock(blockBuf, BLOCK_FRAMES);
                float gain = computeGain(currentLocal);
                for (int i = 0; i < accum.length; i++) accum[i] += blockBuf[i] * gain;
            }

            for (TrackState ts : sources) {
                if (ts.resampler.isFinished() || fadeProgress(ts) >= 1.0) {
                    toRemove.add(ts);
                    continue;
                }
                ts.resampler.fillBlock(blockBuf, BLOCK_FRAMES);
                float gain = computeGain(ts);
                for (int i = 0; i < accum.length; i++) accum[i] += blockBuf[i] * gain;
            }

            if (!toRemove.isEmpty()) {
                synchronized (lock) { outgoing.removeAll(toRemove); }
            }

            for (int i = 0; i < accum.length; i++) {
                int v = Math.round(accum[i] * 32767f);
                v = Math.max(-32768, Math.min(32767, v));
                out[i * 2] = (byte) (v & 0xFF);
                out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
            }
            line.write(out, 0, out.length);
        }
    }

    private static float computeGain(TrackState ts) {
        double fadeFactor = 1.0;
        if (ts.fadeDurationNanos > 0) {
            double progress = fadeProgress(ts);
            fadeFactor = ts.fadingIn ? progress : (1.0 - progress);
        }
        double linear = cachedMusicVolume * Math.pow(10.0, ts.offsetDb / 20.0) * fadeFactor;
        return (float) Math.max(0.0, Math.min(1.5, linear));
    }

    private static double fadeProgress(TrackState ts) {
        if (ts.fadeDurationNanos <= 0) return 1.0;
        long elapsed = System.nanoTime() - ts.fadeStartNanos;
        return Math.min(1.0, elapsed / (double) ts.fadeDurationNanos);
    }

    public static void preload(Path file) {
        if (file == null || preloadedTracks.containsKey(file)) return;
        java.util.concurrent.CompletableFuture<AudioTrack> future = new java.util.concurrent.CompletableFuture<>();
        preloadedTracks.put(file, future);
        PRELOAD_EXECUTOR.submit(() -> {
            try {
                // Считаем громкость здесь же, в фоновом потоке — если результата ещё нет в кэше,
                // это самая тяжёлая часть подготовки трека, и делать её на тик-потоке нельзя
                TrackVolumeManager.getGainOffsetDb(file);
                AudioTrack t = AudioTrack.open(file);
                future.complete(t);
            } catch (Exception e) {
                e.printStackTrace();
                future.completeExceptionally(e);
            }
        });
    }

    public static boolean crossfadeTo(Path file) {
        return crossfadeTo(file, ModConfig.get().crossfadeEnabled, ModConfig.get().crossfadeDurationSeconds);
    }

    // forceFade/forceFadeDurationSeconds позволяют переопределить обычную настройку кроссфейда
// (используется для плавного появления звука при запуске игры)
    public static boolean crossfadeTo(Path file, boolean forceFade, double forceFadeDurationSeconds) {
        init();
        java.util.concurrent.CompletableFuture<AudioTrack> pending = preloadedTracks.remove(file);
        AudioTrack track;
        try {
            if (pending != null) {
                track = pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                track = AudioTrack.open(file);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        boolean fade = forceFade;
        long fadeNanos = fade ? (long) (Math.max(0.1, forceFadeDurationSeconds) * 1_000_000_000L) : 0L;

        TrackState newState = new TrackState();
        newState.resampler = new TrackResampler(track, OUTPUT_SAMPLE_RATE);
        newState.offsetDb = TrackVolumeManager.getGainOffsetDb(file);
        newState.fadeDurationNanos = fadeNanos;
        newState.fadeStartNanos = System.nanoTime();
        newState.fadingIn = fade;

        synchronized (lock) {
            if (current != null) {
                if (fade) {
                    current.fadingIn = false;
                    current.fadeStartNanos = System.nanoTime();
                    current.fadeDurationNanos = fadeNanos;
                    outgoing.add(current);
                }
            }
            current = newState;
        }
        return true;
    }

    public static void stop() {
        synchronized (lock) {
            current = null;
            outgoing.clear();
        }
    }

    public static boolean isBusy() {
        synchronized (lock) {
            return current != null && !current.resampler.isFinished();
        }
    }

    public static void tickVolumeSync() {
        cachedMusicVolume = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MUSIC);
    }
}