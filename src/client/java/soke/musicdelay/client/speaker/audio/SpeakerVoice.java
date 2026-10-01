package soke.musicdelay.client.speaker.audio;

import soke.musicdelay.client.AudioTrack;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** One movable source. Only its worker opens, reads and closes audio resources.
 * The caller owns lifecycle, source selection, world clocks and the total voice limit.
 * No Minecraft objects are read by this worker. Construct one voice per playback session.
 */
public final class SpeakerVoice implements AutoCloseable {
    @FunctionalInterface public interface Source { AudioTrack open() throws Exception; }
    public enum Status { NEW, OPENING, PLAYING, PAUSED, FINISHED, STOPPED, FAILED }
    public record Control(SpeakerPcm.Levels levels, boolean paused) {
        public Control { Objects.requireNonNull(levels); }
    }
    private static final int FRAMES = 512;
    private final UUID id;
    private final Source source;
    private final long startMillis;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Control control = new Control(SpeakerPcm.Levels.SILENT, true);
    private volatile boolean stopped;
    private volatile Status status = Status.NEW;
    private volatile Exception failure;
    private volatile long positionMillis;

    public SpeakerVoice(UUID id, Source source, long startMillis) {
        this.id = Objects.requireNonNull(id);
        this.source = Objects.requireNonNull(source);
        if (startMillis < 0 || startMillis > 86_400_000) throw new IllegalArgumentException("Invalid start position");
        this.startMillis = startMillis;
        positionMillis = startMillis;
    }
    public UUID id() { return id; }
    public Status status() { return status; }
    public Exception failure() { return failure; }
    public long positionMillis() { return positionMillis; }
    public void update(Control value) { control = Objects.requireNonNull(value); }
    public void start() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Already started");
        if (stopped) { status = Status.STOPPED; return; }
        status = Status.OPENING;
        Thread worker = new Thread(this::run, "always-play-speaker-" + id);
        worker.setDaemon(true);
        worker.start();
    }
    /** Nonblocking. Cleanup completes on the audio worker; never close a device on a game tick. */
    @Override public void close() { stopped = true; if (!started.get()) status = Status.STOPPED; }

    private void run() {
        AudioTrack track = null;
        SourceDataLine line = null;
        try {
            if (stopped) return;
            track = source.open();
            if (stopped) return;
            int channels = track.getChannels();
            float rate = track.getSampleRate();
            if (!Float.isFinite(rate) || rate <= 0) throw new IllegalArgumentException("Invalid sample rate");
            byte[] input = new byte[FRAMES * channels * 2];
            byte[] output = new byte[FRAMES * 4];
            long seekFrames = (long) (startMillis * (double) rate / 1000);
            long skipped = 0;
            // Exact frame seek, with no block-size rounding or buffering of the whole song.
            while (!stopped && skipped < seekFrames) {
                int want = (int) Math.min(FRAMES, seekFrames - skipped);
                byte[] part = want == FRAMES ? input : new byte[want * channels * 2];
                int read = track.read(part);
                if (read < 0) { status = Status.FINISHED; return; }
                skipped += read / (channels * 2);
            }
            if (stopped) return;
            line = AudioSystem.getSourceDataLine(new AudioFormat(rate, 16, 2, true, false));
            line.open(new AudioFormat(rate, 16, 2, true, false), FRAMES * 4 * 4);
            long initialFrame = line.getLongFramePosition();
            SpeakerPcm.Levels previous = SpeakerPcm.Levels.SILENT;
            boolean running = false;
            while (!stopped) {
                Control current = control;
                if (current.paused) {
                    if (running) { line.stop(); running = false; }
                    status = Status.PAUSED;
                    positionMillis = startMillis + (long) ((line.getLongFramePosition() - initialFrame) * 1000.0 / rate);
                    Thread.sleep(5); continue;
                }
                if (!running) { line.start(); running = true; }
                status = Status.PLAYING;
                int count = track.read(input);
                if (count < 0) break;
                int bytes = SpeakerPcm.mix(input, count, channels, output, previous, current.levels);
                previous = current.levels;
                int offset = 0;
                while (!stopped && offset < bytes) {
                    if (control.paused) {
                        if (running) { line.stop(); running = false; }
                        status = Status.PAUSED;
                        Thread.sleep(5); continue;
                    }
                    if (!running) { line.start(); running = true; }
                    status = Status.PLAYING;
                    int available = Math.min(line.available(), bytes - offset) & ~3;
                    if (available == 0) { Thread.sleep(2); continue; }
                    int written = line.write(output, offset, available);
                    if (written <= 0) { Thread.sleep(2); continue; }
                    offset += written;
                    positionMillis = startMillis + (long) ((line.getLongFramePosition() - initialFrame) * 1000.0 / rate);
                }
            }
            // Drain cooperatively: SourceDataLine.drain() can block shutdown indefinitely.
            while (!stopped && line.available() < line.getBufferSize()) {
                if (control.paused && running) { line.stop(); running = false; }
                if (!control.paused && !running) { line.start(); running = true; }
                status = control.paused ? Status.PAUSED : Status.PLAYING;
                positionMillis = startMillis + (long) ((line.getLongFramePosition() - initialFrame) * 1000.0 / rate);
                Thread.sleep(5);
            }
            positionMillis = startMillis + (long) ((line.getLongFramePosition() - initialFrame) * 1000.0 / rate);
            status = Status.FINISHED;
        } catch (Exception e) {
            if (!stopped) { failure = e; status = Status.FAILED; }
        } finally {
            try {
                if (line != null) { try { line.stop(); line.flush(); } finally { line.close(); } }
            } catch (Exception cleanup) {
                if (!stopped && failure == null) { failure = cleanup; status = Status.FAILED; }
            } finally {
                if (track != null) track.close();
                if (stopped) status = Status.STOPPED;
            }
        }
    }
}
