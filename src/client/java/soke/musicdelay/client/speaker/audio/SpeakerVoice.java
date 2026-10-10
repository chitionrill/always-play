package soke.musicdelay.client.speaker.audio;

import soke.musicdelay.client.AudioTrack;
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
    public record Control(SpeakerPcm.Levels levels, boolean paused, boolean chest, float openness, boolean doubleChest, boolean copper, boolean shell, boolean ender, float environmentGain, float environmentCutoff, java.util.List<SpeakerPropagation.Reflection> reflections,SpeakerWater.Profile water) {
        public Control { Objects.requireNonNull(levels); Objects.requireNonNull(water); reflections=java.util.List.copyOf(reflections); openness=Float.isFinite(openness)?Math.clamp(openness,0,1):0; }
        public Control(SpeakerPcm.Levels levels, boolean paused) { this(levels,paused,false,0,false,false,false,false,1,20000,java.util.List.of(),SpeakerWater.Profile.DRY); }
        public Control(SpeakerPcm.Levels levels,boolean paused,boolean chest,float openness,boolean doubleChest,boolean copper,boolean shell,boolean ender,float environmentGain,float environmentCutoff,java.util.List<SpeakerPropagation.Reflection> reflections){
            this(levels,paused,chest,openness,doubleChest,copper,shell,ender,environmentGain,environmentCutoff,reflections,SpeakerWater.Profile.DRY);
        }
    }
    private static final int FRAMES = 512;
    private final UUID id;
    private final Source source;
    private final SpeakerMixer mixer;
    private final long startMillis;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Control control = new Control(SpeakerPcm.Levels.SILENT, true);
    private volatile boolean stopped,released;
    private volatile Status status = Status.NEW;
    private volatile Exception failure;
    private volatile long positionMillis;

    public SpeakerVoice(UUID id, Source source, long startMillis) {
        this(id,source,startMillis,SpeakerMixer.shared());
    }
    public SpeakerVoice(UUID id, Source source, long startMillis,SpeakerMixer mixer) {
        this.mixer=Objects.requireNonNull(mixer);
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

    /** Stop feeding music but allow already emitted energy to finish its finite response. */
    public void release(){released=true;if(!started.get())close();}

    private void run() {
        AudioTrack track = null;
        SpeakerMixer.Port line = null;
        try {
            if (stopped) return;
            track = source.open();
            track = track.resample(SpeakerMixer.RATE);
            if (stopped) return;
            int channels = track.getChannels();
            float rate = track.getSampleRate();
            if (!Float.isFinite(rate) || rate <= 0) throw new IllegalArgumentException("Invalid sample rate");
            byte[] input = new byte[FRAMES * channels * 2];
            byte[] output = new byte[FRAMES * 4];
            long seekFrames = (long) (startMillis * (double) rate / 1000);
            long skipped = 0;
            // Exact frame seek, with no block-size rounding or buffering of the whole song.
            while (!stopped && !released && skipped < seekFrames) {
                int want = (int) Math.min(FRAMES, seekFrames - skipped);
                byte[] part = want == FRAMES ? input : new byte[want * channels * 2];
                int read = track.read(part);
                if (read < 0) { status = Status.FINISHED; return; }
                skipped += read / (channels * 2);
            }
            if (stopped) return;
            if(released){status=Status.STOPPED;return;}
            line = mixer.open();
            SpeakerPcm.Levels previous = SpeakerPcm.Levels.SILENT;
            SpeakerChestFilter chestFilter = new SpeakerChestFilter(rate,channels);
            SpeakerEnderFilter enderFilter = new SpeakerEnderFilter(rate,channels);
            SpeakerEnvironmentFilter environmentFilter = new SpeakerEnvironmentFilter(rate,channels);
            SpeakerWater.Filter waterFilter=new SpeakerWater.Filter(rate,channels);
            int tailRemaining=-1;
            while (!stopped) {
                if(line.failure()!=null)throw line.failure();
                Control current = control;
                if (current.paused && !released) {
                    line.pause(true);
                    status = Status.PAUSED;
                    positionMillis = startMillis + (long) (line.playedFrames() * 1000.0 / rate);
                    Thread.sleep(5); continue;
                }
                line.pause(false);
                status = Status.PLAYING;
                if(released && tailRemaining<0)tailRemaining=environmentFilter.tailFrames();
                int count;
                if(tailRemaining>=0){
                    if(tailRemaining==0)break;
                    int frames=Math.min(FRAMES,tailRemaining);count=frames*channels*2;
                    java.util.Arrays.fill(input,0,count,(byte)0);tailRemaining-=frames;
                } else {
                    count=track.read(input);
                    if(count<0){tailRemaining=environmentFilter.tailFrames();continue;}
                    chestFilter.process(input,count,current.chest,current.openness,current.doubleChest,current.copper,current.shell);
                    enderFilter.process(input,count,current.ender,current.openness);
                }
                environmentFilter.process(input,count,current.environmentGain,current.environmentCutoff,current.reflections);
                waterFilter.process(input,count,current.water);
                var next = SpeakerPcm.smooth(previous,current.water.spatial(current.levels),count/(channels*2),rate);
                int bytes = SpeakerPcm.mix(input, count, channels, output, previous, next);
                previous = next;
                int offset = 0;
                while (!stopped && offset < bytes) {
                    if (control.paused && !released) {
                        line.pause(true);
                        status = Status.PAUSED;
                        Thread.sleep(5); continue;
                    }
                    line.pause(false);
                    status = Status.PLAYING;
                    if(line.failure()!=null)throw line.failure();
                    int written = line.offer(output, offset, bytes-offset);
                    if (written <= 0) { Thread.sleep(2); continue; }
                    offset += written;
                    positionMillis = startMillis + (long) (line.playedFrames() * 1000.0 / rate);
                }
            }
            // Wait only for this source's queue and device spans, never for other songs.
            while (!stopped && !line.drained()) {
                if(line.failure()!=null)throw line.failure();
                line.pause(control.paused && !released);
                status = control.paused && !released ? Status.PAUSED : Status.PLAYING;
                positionMillis = startMillis + (long) (line.playedFrames() * 1000.0 / rate);
                Thread.sleep(5);
            }
            positionMillis = startMillis + (long) (line.playedFrames() * 1000.0 / rate);
            status = Status.FINISHED;
        } catch (Exception e) {
            if (!stopped && !released) { failure = e; status = Status.FAILED; }
        } finally {
            try {
                if (line != null) line.close();
            } catch (Exception cleanup) {
                if (!stopped && !released && failure == null) { failure = cleanup; status = Status.FAILED; }
            } finally {
                if (track != null) track.close();
                if (stopped || (released && status!=Status.FINISHED)) status = Status.STOPPED;
            }
        }
    }
}
