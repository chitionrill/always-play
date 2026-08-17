package soke.musicdelay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import soke.musicdelay.ModConfig;

import javax.sound.sampled.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class WavPlayer {

    private static final int OUTPUT_SAMPLE_RATE = 44100;
    private static final AudioFormat OUTPUT_FORMAT = new AudioFormat(OUTPUT_SAMPLE_RATE, 16, 2, true, false);
    private static final int BLOCK_FRAMES = 1024;

    private static SourceDataLine line;
    private static Thread engineThread;
    private static volatile boolean running = false;
    private static volatile boolean paused = false; // новое
    private static volatile float cachedMusicVolume = 1.0f;

    private static class TrackState {
        TrackResampler resampler;
        volatile double offsetDb; // может обновляться фоновым потоком после старта проигрывания
        long fadeStartNanos;
        long fadeDurationNanos;
        boolean fadingIn;
    }

    private static TrackState current;
    private static final List<TrackState> outgoing = new ArrayList<>();
    private static final Object lock = new Object();

    // Раньше было 4 — этого хватало на "один трек вперёд". Теперь TrackPreloadManager держит
    // прогретым окно до TrackPreloadManager.WINDOW треков вперёд + немного запаса на случай,
    // пока старое ещё не вытеснилось, новое уже добавляется.
    private static final int MAX_PRELOADED = 16;
    private static final Object preloadMapLock = new Object();

    // LRU-кэш прогретых, но ещё не сыгранных треков. Раньше это была обычная
    // ConcurrentHashMap без ограничения размера — если трек прогревался (например,
    // как следующий в плейлисте), а потом реально проигран не был (плейлист сменили,
    // юзер кликнул по другому треку), декодированный AudioTrack оставался висеть в
    // памяти/на диске (открытый поток) до бесконечности. Теперь при превышении
    // MAX_PRELOADED самый старый неиспользованный элемент вытесняется и закрывается.
    private static final Map<Path, CompletableFuture<AudioTrack>> preloadedTracks =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, CompletableFuture<AudioTrack>> eldest) {
                    boolean evict = size() > MAX_PRELOADED;
                    if (evict) {
                        eldest.getValue().thenAccept(track -> {
                            if (track != null) track.close();
                        });
                        preloadTasks.remove(eldest.getKey());
                    }
                    return evict;
                }
            };

    // Параллельно preloadedTracks храним сами Future от submit() в executor — нужно,
    // чтобы уметь отменять ещё НЕ начавшуюся задачу (single-thread executor, значит задачи,
    // стоящие в очереди после текущей, реально можно снять без траты CPU). Используется
    // TrackPreloadManager при spam-скипе/изменении очереди, когда трек вышел из окна prefetch.
    private static final Map<Path, Future<?>> preloadTasks = new java.util.HashMap<>();

    private static final ExecutorService PRELOAD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mdr-audio-preload");
        t.setDaemon(true);
        return t;
    });

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
            if (paused) {
                // Ничего не двигаем — ни current, ни outgoing — так и позиция не уезжает,
                // просто шлём тишину, чтобы линия не буферизовалась/не глохла
                Arrays.fill(out, (byte) 0);
                line.write(out, 0, out.length);
                continue;
            }

            TrackState currentLocal;
            List<TrackState> sources;
            synchronized (lock) {
                currentLocal = current;
                sources = new ArrayList<>(outgoing);
            }

            Arrays.fill(accum, 0f);
            List<TrackState> toRemove = new ArrayList<>();

            if (currentLocal != null) {
                if (currentLocal.resampler.isFinished()) {
                    // Трек доиграл сам по себе (не был снят кроссфейдом) — закрываем декодер
                    // и снимаем ссылку, чтобы не держать открытый файл/поток вхолостую.
                    synchronized (lock) {
                        if (current == currentLocal) current = null;
                    }
                    closeState(currentLocal);
                } else {
                    currentLocal.resampler.fillBlock(blockBuf, BLOCK_FRAMES);
                    float gain = computeGain(currentLocal);
                    for (int i = 0; i < accum.length; i++) accum[i] += blockBuf[i] * gain;
                }
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
                for (TrackState ts : toRemove) closeState(ts);
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
        if (file == null) return;
        CompletableFuture<AudioTrack> future;
        synchronized (preloadMapLock) {
            if (preloadedTracks.containsKey(file)) return;
            future = new CompletableFuture<>();
            preloadedTracks.put(file, future);
        }
        CompletableFuture<AudioTrack> finalFuture = future;
        Future<?> task = PRELOAD_EXECUTOR.submit(() -> {
            try {
                // Считаем громкость здесь же, в фоновом потоке — если результата ещё нет в кэше,
                // это самая тяжёлая часть подготовки трека, и делать её на тик-потоке нельзя
                TrackVolumeManager.getGainOffsetDb(file);
                AudioTrack t = AudioTrack.open(file);
                finalFuture.complete(t);
            } catch (Exception e) {
                e.printStackTrace();
                finalFuture.completeExceptionally(e);
            } finally {
                synchronized (preloadMapLock) {
                    preloadTasks.remove(file);
                }
            }
        });
        synchronized (preloadMapLock) {
            // Если crossfadeTo() успел забрать трек из preloadedTracks, пока мы были здесь —
            // не держим ссылку на задачу, которая уже никому не принадлежит
            if (preloadedTracks.containsKey(file)) {
                preloadTasks.put(file, task);
            }
        }
    }

    // Не готов ли трек к МГНОВЕННОМУ воспроизведению прямо сейчас — не блокирует поток.
    public static boolean isReady(Path file) {
        synchronized (preloadMapLock) {
            CompletableFuture<AudioTrack> f = preloadedTracks.get(file);
            return f != null && f.isDone() && !f.isCompletedExceptionally();
        }
    }

    // Снимает трек из окна prefetch, если он туда больше не входит (spam-скип, смена очереди).
    // Если фоновая задача ещё не стартовала — реально экономит CPU, отменяя её.
    // Если уже выполняется — доработает вхолостую (дёшево: open()+RMS одного файла),
    // результат просто останется невостребованным и будет вытеснен LRU.
    public static void cancelPreload(Path file) {
        if (file == null) return;
        CompletableFuture<AudioTrack> removedFuture;
        Future<?> task;
        synchronized (preloadMapLock) {
            removedFuture = preloadedTracks.remove(file);
            task = preloadTasks.remove(file);
        }
        if (task != null) {
            task.cancel(false);
        }
        if (removedFuture != null) {
            removedFuture.thenAccept(track -> {
                if (track != null) track.close();
            });
        }
    }

    public static boolean crossfadeTo(Path file) {
        return crossfadeTo(file, ModConfig.get().crossfadeEnabled, ModConfig.get().crossfadeDurationSeconds);
    }

    // forceFade/forceFadeDurationSeconds позволяют переопределить обычную настройку кроссфейда
// (используется для плавного появления звука при запуске игры)
    public static boolean crossfadeTo(Path file, boolean forceFade, double forceFadeDurationSeconds) {
        init();

        AudioTrack track;
        synchronized (preloadMapLock) {
            CompletableFuture<AudioTrack> pending = preloadedTracks.get(file);
            if (pending != null && pending.isDone() && !pending.isCompletedExceptionally()) {
                // Готов — забираем немедленно, join() тут не блокирует, т.к. future уже done
                preloadedTracks.remove(file);
                preloadTasks.remove(file);
                track = pending.join();
            } else {
                // Не готов (либо ещё готовится, либо вообще не запрашивался) — НЕ ждём и
                // не открываем файл синхронно на вызывающем (тик) потоке. Раньше здесь был
                // pending.get(5, SECONDS) — блокировка до 5 секунд, из-за которой ручной скип
                // фризил игру, если prefetch не успевал. Вместо этого просто просим подготовить
                // (no-op, если уже готовится) и возвращаем false — вызывающий код (TrackPlaybackService)
                // должен на false уйти в тот же short-wait путь, что и обычное докручивание трека.
                track = null;
            }
        }

        if (track == null) {
            preload(file); // no-op если уже в процессе
            return false;
        }

        boolean fade = forceFade;
        long fadeNanos = fade ? (long) (Math.max(0.1, forceFadeDurationSeconds) * 1_000_000_000L) : 0L;

        TrackState newState = new TrackState();
        newState.resampler = new TrackResampler(track, OUTPUT_SAMPLE_RATE);
        newState.fadeDurationNanos = fadeNanos;
        newState.fadeStartNanos = System.nanoTime();
        newState.fadingIn = fade;

        // Если громкость трека уже посчитана раньше — это просто чтение из мапы (дёшево).
        // Если нет — НЕ считаем её здесь синхронно (это полный декод файла и фриз тик-потока),
        // а стартуем с offsetDb = 0 и досчитываем в фоне, подставляя результат "на лету".
        if (TrackVolumeManager.isCached(file)) {
            newState.offsetDb = TrackVolumeManager.getGainOffsetDb(file);
        } else {
            newState.offsetDb = 0.0;
            PRELOAD_EXECUTOR.submit(() -> {
                try {
                    newState.offsetDb = TrackVolumeManager.getGainOffsetDb(file);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        synchronized (lock) {
            if (current != null) {
                if (fade) {
                    current.fadingIn = false;
                    current.fadeStartNanos = System.nanoTime();
                    current.fadeDurationNanos = fadeNanos;
                    outgoing.add(current);
                } else {
                    // Жёсткая смена без кроссфейда — старый трек не идёт в outgoing
                    // и больше никогда не отрисуется, поэтому закрываем его сразу.
                    closeState(current);
                }
            }
            current = newState;
        }
        return true;
    }

    private static void closeState(TrackState ts) {
        if (ts != null) {
            ts.resampler.getTrack().close();
        }
    }

    public static void stop() {
        synchronized (lock) {
            closeState(current);
            for (TrackState ts : outgoing) closeState(ts);
            current = null;
            outgoing.clear();
        }
    }

    public static boolean isBusy() {
        synchronized (lock) {
            return current != null && !current.resampler.isFinished();
        }
    }

    public static void pause() {
        paused = true;
    }

    public static void resume() {
        paused = false;
    }

    public static boolean isPaused() {
        return paused;
    }

    public static void tickVolumeSync() {
        cachedMusicVolume = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MUSIC);
    }
}