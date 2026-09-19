package soke.musicdelay.client.jukebox;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import soke.musicdelay.MusicDelayReducer;
import soke.musicdelay.client.AudioTrack;
import soke.musicdelay.client.playback.JukeboxDuckController;
import javax.sound.sampled.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Only the worker owns audio resources; the game thread updates controls. */
public class PositionalCustomTrackPlayer {
    @FunctionalInterface public interface AudioSource { AudioTrack open() throws Exception; }
    private static final int FRAMES = 1024;
    private static final Map<BlockPos, PositionalCustomTrackPlayer> active = new ConcurrentHashMap<>();
    private final BlockPos pos;
    private final AudioSource source;
    private final LongSupplier elapsed;
    private volatile boolean stopped;
    private volatile boolean paused = true;
    private volatile boolean audible;
    private volatile float gain;
    private volatile float pan;
    private boolean confirmed;
    private int waitTicks = 40;
    private float previousGain;
    private float previousPan;
    private PositionalCustomTrackPlayer(BlockPos pos, AudioSource source, LongSupplier elapsed) {
        this.pos = pos.immutable(); this.source = source; this.elapsed = elapsed;
    }
    public static void startAt(BlockPos pos, Path file) { startAt(pos, () -> AudioTrack.open(file), () -> 0); }
    public static void startAt(BlockPos pos, AudioTrack track) { startAt(pos, () -> track, () -> 0); }
    public static void startAt(BlockPos pos, AudioSource source, LongSupplier elapsed) {
        stop(pos);
        PositionalCustomTrackPlayer player = new PositionalCustomTrackPlayer(pos, source, elapsed);
        active.put(player.pos, player);
        Thread thread = new Thread(player::run, "mdr-jukebox-" + pos);
        thread.setDaemon(true); thread.start();
    }
    public static void stop(BlockPos pos) {
        PositionalCustomTrackPlayer player = active.remove(pos);
        if (player != null) { player.stopped = true; JukeboxDuckController.onJukeboxSoundStop(pos); }
    }
    public static void stopAll() { for (BlockPos pos : Map.copyOf(active).keySet()) stop(pos); }
    public static boolean hasCapacity() { return active.size() < 16; }
    public static void tick(Minecraft client, int radius) {
        if (client.level == null || client.player == null) { stopAll(); return; }
        for (PositionalCustomTrackPlayer player : active.values()) player.update(client, radius);
    }
    private void update(Minecraft client, int radius) {
        if (!client.level.isLoaded(pos)) {
            paused = true; gain = 0; JukeboxDuckController.onJukeboxSoundStop(pos); return;
        }
        var state = client.level.getBlockState(pos);
        boolean record = state.is(Blocks.JUKEBOX) && state.hasProperty(JukeboxBlock.HAS_RECORD) && state.getValue(JukeboxBlock.HAS_RECORD);
        if (!record) {
            if (!confirmed && --waitTicks > 0) return;
            stop(pos); return;
        }
        confirmed = true; paused = client.isPaused();
        if (audible) JukeboxDuckController.onJukeboxSoundStart(pos);
        double normalized = Math.min(1, Math.sqrt(pos.distSqr(client.player.blockPosition())) / Math.max(1, radius));
        gain = (float) (1 - normalized * normalized) * client.options.getSoundSourceVolume(SoundSource.MASTER)
                * client.options.getSoundSourceVolume(SoundSource.RECORDS);
        double dx = pos.getX() + 0.5 - client.player.getX(), dz = pos.getZ() + 0.5 - client.player.getZ();
        double length = Math.hypot(dx, dz), yaw = Math.toRadians(client.player.getYRot());
        double right = length < 0.0001 ? 0 : (-dx * Math.cos(yaw) - dz * Math.sin(yaw)) / length;
        pan = (float) Math.clamp(right, -0.85, 0.85);
    }
    private void run() {
        AudioTrack track = null; SourceDataLine line = null;
        try {
            track = source.open();
            if (stopped) return;
            AudioFormat format = new AudioFormat(track.getSampleRate(), 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(format); line.open(format, FRAMES * 4 * 4);
            byte[] input = new byte[FRAMES * track.getChannels() * 2], output = new byte[FRAMES * 4];
            long skippedFrames = 0;
            while (!stopped && skippedFrames * 1000.0 / track.getSampleRate() + 25 < Math.clamp(elapsed.getAsLong(), 0, 1_800_000)) {
                int read = track.read(input); if (read < 0) return;
                skippedFrames += read / (track.getChannels() * 2);
            }
            boolean running = false;
            while (!stopped) {
                if (paused) {
                    if (running) { line.stop(); running = false; }
                    Thread.sleep(5); continue;
                }
                if (!running) { line.start(); running = true; }
                int count = track.read(input); if (count < 0) break;
                int frames = count / (track.getChannels() * 2);
                float endGain = gain, endPan = pan;
                for (int i = 0; i < frames; i++) {
                    float t = (i + 1f) / frames;
                    float g = previousGain + (endGain - previousGain) * t;
                    double angle = (previousPan + (endPan - previousPan) * t + 1) * Math.PI / 4;
                    int index = i * track.getChannels() * 2;
                    int left = sample(input, index), right = track.getChannels() == 2 ? sample(input, index + 2) : left;
                    put(output, i * 4, Math.round(left * g * (float) Math.cos(angle)));
                    put(output, i * 4 + 2, Math.round(right * g * (float) Math.sin(angle)));
                }
                previousGain = endGain; previousPan = endPan;
                int offset = 0;
                while (!stopped && offset < frames * 4) {
                    if (paused) {
                        if (running) { line.stop(); running = false; }
                        Thread.sleep(5); continue;
                    }
                    if (!running) { line.start(); running = true; }
                    int available = Math.min(line.available(), frames * 4 - offset);
                    available -= available % 4;
                    if (available == 0) { Thread.sleep(2); continue; }
                    int written = line.write(output, offset, available);
                    if (written <= 0) { Thread.sleep(2); continue; }
                    offset += written; audible = true;
                }
            }
            while (!stopped && line.available() < line.getBufferSize()) {
                if (paused && running) { line.stop(); running = false; }
                else if (!paused && !running) { line.start(); running = true; }
                Thread.sleep(5);
            }
        } catch (Exception e) {
            if (!stopped) {
                MusicDelayReducer.LOGGER.warn("Cannot play jukebox audio at " + pos, e);
                Minecraft.getInstance().execute(() -> { if (active.get(pos) == this) SharedJukeboxClient.message("audio_failed"); });
            }
        } finally {
            try { if (line != null) { line.stop(); line.flush(); line.close(); } }
            finally {
                if (track != null) track.close();
                Minecraft.getInstance().execute(() -> {
                    if (active.remove(pos, this)) JukeboxDuckController.onJukeboxSoundStop(pos);
                });
            }
        }
    }
    private static int sample(byte[] bytes, int offset) { return (short) ((bytes[offset] & 255) | (bytes[offset + 1] << 8)); }
    private static void put(byte[] bytes, int offset, int value) {
        value = Math.clamp(value, -32768, 32767); bytes[offset] = (byte) value; bytes[offset + 1] = (byte) (value >> 8);
    }
}
