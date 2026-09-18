package soke.musicdelay.client.jukebox;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import soke.musicdelay.ModConfig;
import soke.musicdelay.MusicDelayReducer;
import soke.musicdelay.client.AudioTrack;
import soke.musicdelay.client.playback.JukeboxDuckController;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One playback instance per jukebox, independent of Minecraft's sound engine. */
public class PositionalCustomTrackPlayer {
    private static final int BUFFER_FRAMES = 1024;
    private static final float MAX_PAN = 0.85f;
    private static final Map<BlockPos, PositionalCustomTrackPlayer> activeByPos = new ConcurrentHashMap<>();

    private final BlockPos pos;
    private final AudioTrack track;
    private final SourceDataLine line;
    private volatile boolean stopRequested;
    private volatile boolean paused = true;

    // Ждём подтверждения вставки пластинки на клиенте.
// Проверяется в игровом потоке, максимум 40 проверок.
    private boolean recordStateConfirmed = false;
    private int recordStateWaitTicks = 40;
    private volatile float targetGain;
    private volatile float targetPan;
    private float currentGain;
    private float currentPan;

    private PositionalCustomTrackPlayer(BlockPos pos, AudioTrack track, SourceDataLine line) {
        this.pos = pos.immutable();
        this.track = track;
        this.line = line;
    }

    public static void startAt(BlockPos pos, Path filePath) {
        try {
            startAt(pos, AudioTrack.open(filePath));
        } catch (Exception e) {
            MusicDelayReducer.LOGGER.error("Cannot open jukebox file: " + filePath, e);
        }
    }

    // Takes ownership of the decoder, even if the output cannot be opened.
    public static void startAt(BlockPos pos, AudioTrack track) {
        stop(pos);
        SourceDataLine line = null;
        try {
            AudioFormat format = new AudioFormat(track.getSampleRate(), 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, BUFFER_FRAMES * 4 * 4);
            PositionalCustomTrackPlayer player = new PositionalCustomTrackPlayer(pos, track, line);
            activeByPos.put(player.pos, player);
            MusicDelayReducer.LOGGER.info(
                    "[Jukebox] Audio opened at {}: sampleRate={}, channels={}",
                    player.pos, track.getSampleRate(), track.getChannels()
            );
            player.tickOne(Minecraft.getInstance(), ModConfig.get().jukeboxDetectionRadius);
            Thread thread = new Thread(player::runLoop, "mdr-jukebox-" + pos);
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            if (line != null) line.close();
            track.close();
            PositionalCustomTrackPlayer existing = activeByPos.get(pos);
            if (existing != null && existing.track == track && activeByPos.remove(pos, existing)) {
                JukeboxDuckController.onJukeboxSoundStop(pos);
            }
            MusicDelayReducer.LOGGER.error("Cannot start jukebox audio", e);
        }
    }

    public static void stop(BlockPos pos) {
        PositionalCustomTrackPlayer player = activeByPos.remove(pos);
        if (player == null) return;

        player.stopRequested = true;
        player.targetGain = 0.0f;
        JukeboxDuckController.onJukeboxSoundStop(pos);

        // Игровой поток не должен ждать аудиоустройство.
        Thread cleanupThread = new Thread(() -> {
            long started = System.nanoTime();

            try {
                player.line.stop();
                player.line.flush();
            } catch (Exception e) {
                MusicDelayReducer.LOGGER.warn(
                        "[Jukebox] Error stopping audio at " + player.pos, e
                );
            } finally {
                try {
                    player.line.close();
                } catch (Exception e) {
                    MusicDelayReducer.LOGGER.warn(
                            "[Jukebox] Error closing audio at " + player.pos, e
                    );
                }

                long elapsedMs =
                        (System.nanoTime() - started) / 1_000_000L;

                if (elapsedMs >= 50) {
                    MusicDelayReducer.LOGGER.warn(
                            "[Jukebox] Audio shutdown took {} ms at {}",
                            elapsedMs, player.pos
                    );
                }
            }
        }, "mdr-jukebox-close-" + player.pos);

        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    public static void stopAll() {
        for (BlockPos pos : Map.copyOf(activeByPos).keySet()) stop(pos);
    }

    public static void tick(Minecraft client, int detectionRadius) {
        if (client.level == null || client.player == null) {
            stopAll();
            return;
        }
        for (PositionalCustomTrackPlayer player : activeByPos.values()) {
            player.tickOne(client, detectionRadius);
        }
    }

    private void tickOne(Minecraft client, int radius) {
        if (client.level == null || client.player == null) {
            stop(pos);
            return;
        }
        if (!client.level.isLoaded(pos)) {
            paused = true;
            targetGain = 0;
            JukeboxDuckController.onJukeboxSoundStop(pos);
            return;
        }
        var state = client.level.getBlockState(pos);
        boolean hasRecord = state.is(Blocks.JUKEBOX)
                && state.hasProperty(JukeboxBlock.HAS_RECORD)
                && state.getValue(JukeboxBlock.HAS_RECORD);

        if (!hasRecord) {
            if (!recordStateConfirmed) {
                paused = true;
                targetGain = 0.0f;

                if (--recordStateWaitTicks > 0) {
                    return;
                }

                MusicDelayReducer.LOGGER.warn(
                        "[Jukebox] Start cancelled: record state was not received at {}. State: {}",
                        pos, state
                );
            }

            stop(pos);
            return;
        }

        if (!recordStateConfirmed) {
            recordStateConfirmed = true;
            MusicDelayReducer.LOGGER.info(
                    "[Jukebox] Record confirmed, enabling playback at {}", pos
            );
        }

        paused = client.isPaused();
        JukeboxDuckController.onJukeboxSoundStart(pos);

        // Use the same distance convention as JukeboxDuckController.
        double normalized = Math.min(1.0,
                Math.sqrt(pos.distSqr(client.player.blockPosition())) / Math.max(1, radius));
        targetGain = (float) (1.0 - normalized * normalized)
                * client.options.getSoundSourceVolume(SoundSource.MASTER)
                * client.options.getSoundSourceVolume(SoundSource.RECORDS);

        double dx = pos.getX() + 0.5 - client.player.getX();
        double dz = pos.getZ() + 0.5 - client.player.getZ();
        double horizontal = Math.hypot(dx, dz);
        double yaw = Math.toRadians(client.player.getYRot());
        // Positive pan means right; at yaw=0, west is to the player's right.
        double right = horizontal < 0.0001 ? 0 : (-dx * Math.cos(yaw) - dz * Math.sin(yaw)) / horizontal;
        targetPan = (float) Math.max(-MAX_PAN, Math.min(MAX_PAN, right));
    }

    private void runLoop() {
        byte[] input = new byte[BUFFER_FRAMES * track.getChannels() * 2];
        byte[] output = new byte[BUFFER_FRAMES * 4];
        boolean ended = false;
        boolean linePaused = false;
        try {
            if (!stopRequested) line.start();
            while (!stopRequested) {
                if (paused) {
                    if (!linePaused) {
                        line.stop();
                        linePaused = true;
                    }
                    Thread.sleep(20);
                    continue;
                }
                if (linePaused) {
                    line.start();
                    linePaused = false;
                }
                int read = track.read(input);
                if (read < 0) { ended = true; break; }
                int channels = track.getChannels();
                int frames = read / (channels * 2);
                float nextGain = targetGain;
                float nextPan = targetPan;
                for (int frame = 0; frame < frames; frame++) {
                    float t = (frame + 1f) / frames;
                    float gain = currentGain + (nextGain - currentGain) * t;
                    float pan = currentPan + (nextPan - currentPan) * t;
                    double angle = (pan + 1.0) * Math.PI / 4.0;
                    int offset = frame * channels * 2;
                    int left = sample(input, offset);
                    int right = channels == 2 ? sample(input, offset + 2) : left;
                    put(output, frame * 4, Math.round(left * gain * (float) Math.cos(angle)));
                    put(output, frame * 4 + 2, Math.round(right * gain * (float) Math.sin(angle)));
                }
                currentGain = nextGain;
                currentPan = nextPan;
                int offset = 0;
                while (!stopRequested && offset < frames * 4) {
                    int written = line.write(output, offset, frames * 4 - offset);
                    if (written <= 0) break;
                    offset += written;
                }
            }
            if (ended && !stopRequested) line.drain();
        } catch (Exception e) {
            if (!stopRequested) MusicDelayReducer.LOGGER.error("Jukebox playback failed", e);
        } finally {
            line.close();
            track.close();
            // Keep map and duck registration changes on the game thread.
            Minecraft.getInstance().execute(() -> {
                if (activeByPos.remove(pos, this)) JukeboxDuckController.onJukeboxSoundStop(pos);
            });
        }
    }

    private static int sample(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 255) | (bytes[offset + 1] << 8));
    }

    private static void put(byte[] bytes, int offset, int value) {
        value = Math.max(-32768, Math.min(32767, value));
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >> 8);
    }
}