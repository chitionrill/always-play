package soke.musicdelay.client.speaker.audio;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import soke.musicdelay.client.AudioTrack;

/** Bounded raw PCM reserve. No position, volume or environment processing happens here. */
public final class SpeakerReadAhead implements AutoCloseable {
    @FunctionalInterface public interface Reader { int read(byte[] target) throws Exception; }
    private record Chunk(byte[] bytes,int count) { }
    private final ArrayBlockingQueue<Chunk> queue=new ArrayBlockingQueue<>(32);
    private final Thread worker;
    private volatile boolean closed,done;
    private volatile Exception failure;
    private final String label;
    public SpeakerReadAhead(AudioTrack track,int bytes,String label){this(track::read,track::close,bytes,label);}
    public SpeakerReadAhead(Reader reader,Runnable cleanup,int bytes,String label){
        if(bytes<=0)throw new IllegalArgumentException();
        this.label=label;
        worker=new Thread(()->{
            try {
                long lastSlow=0;
                while(!closed){
                    byte[] pcm=new byte[bytes];long before=System.nanoTime();int count=reader.read(pcm);
                    long elapsed=System.nanoTime()-before;
                    if(elapsed>50_000_000L && before-lastSlow>5_000_000_000L){
                        soke.musicdelay.MusicDelayReducer.LOGGER.warn("[AlwaysPlay audio] decode-worker-read {}: {}ms, reserveChunks={}",label,elapsed/1_000_000,queue.size());
                        lastSlow=before;
                    }
                    if(SpeakerDiagnostics.recording() && elapsed>10_000_000L)
                        SpeakerDiagnostics.event("decode",label+" ns="+elapsed+" reserveChunks="+queue.size());
                    if(count<0)break;
                    if(count==0){Thread.sleep(1);continue;}
                    if(count>bytes)throw new IllegalStateException("Invalid PCM read size");
                    var chunk=new Chunk(pcm,count);
                    while(!closed && !queue.offer(chunk,20,TimeUnit.MILLISECONDS)) { }
                }
            }catch(InterruptedException e){if(!closed)failure=e;Thread.currentThread().interrupt();}
            catch(Exception e){if(!closed)failure=e;}
            finally{try{cleanup.run();}finally{done=true;}}
        },"always-play-decode-"+label);
        worker.setDaemon(true);worker.start();
    }
    /** Startup reserve is bounded; short files start as soon as EOF has been decoded. */
    public void prime(BooleanSupplier cancelled) throws InterruptedException {
        while(!closed && !done && queue.size()<16 && !cancelled.getAsBoolean())Thread.sleep(2);
    }
    /** 0 means temporary starvation, -1 means EOF. Never substitute silence for missing music. */
    public int read(byte[] target) throws Exception {
        var chunk=queue.poll(10,TimeUnit.MILLISECONDS);
        if(chunk!=null){
            if(target.length<chunk.count)throw new IllegalArgumentException();
            System.arraycopy(chunk.bytes,0,target,0,chunk.count);return chunk.count;
        }
        if(failure!=null)throw failure;
        return done||closed?-1:0;
    }
    public int queuedChunks(){return queue.size();}
    public int queuedBytes(){int total=0;for(var chunk:queue)total+=chunk.count;return total;}
    public boolean terminated(){return done;}
    /** The reader alone owns the stream; closing never blocks a game or mixer thread. */
    @Override public void close(){closed=true;worker.interrupt();}
}
