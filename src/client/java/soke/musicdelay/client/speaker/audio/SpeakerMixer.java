package soke.musicdelay.client.speaker.audio;

import java.util.*;
import javax.sound.sampled.*;

/** One device for speaker audio. Decode workers feed bounded, independently pausable queues. */
public final class SpeakerMixer {
    public static final int RATE=48000;
    private static final int BLOCK=256,CAPACITY=1024;
    public interface Device extends AutoCloseable {
        int available(); int write(byte[] data,int offset,int length);
        long played(); void start(); void stop(); void close();
    }
    @FunctionalInterface public interface Factory { Device open() throws Exception; }
    private static final SpeakerMixer SHARED=new SpeakerMixer(()->{
        var line=AudioSystem.getSourceDataLine(new AudioFormat(RATE,16,2,true,false));
        try {line.open(new AudioFormat(RATE,16,2,true,false),BLOCK*4*4);}
        catch(Exception e){line.close();throw e;}
        return new Device(){
            public int available(){return line.available();}
            public int write(byte[] b,int o,int n){return line.write(b,o,n);}
            public long played(){return line.getLongFramePosition();}
            public void start(){line.start();} public void stop(){line.stop();}
            public void close(){try{line.stop();line.flush();}finally{line.close();}}
        };
    });
    public static SpeakerMixer shared(){return SHARED;}
    private final Factory factory;
    private final List<Port> ports=new ArrayList<>();
    private Thread worker;
    public SpeakerMixer(Factory factory){this.factory=Objects.requireNonNull(factory);}
    public synchronized Port open(){
        var p=new Port();ports.add(p);
        if(worker==null){worker=new Thread(this::run,"always-play-speaker-mixer");worker.setDaemon(true);worker.start();}
        return p;
    }
    private synchronized void remove(Port port){ports.remove(port);}
    public final class Port implements AutoCloseable {
        private final short[] ring=new short[CAPACITY*2];
        private int read,size;
        private boolean paused,closed;
        private volatile Exception failure;
        private long consumed,played;
        private final ArrayDeque<Span> spans=new ArrayDeque<>();
        private record Span(long deviceStart,long sourceStart,int frames) { }
        /** Nonblocking; returns accepted bytes. Never overwrite unplayed frames. */
        public synchronized int offer(byte[] data,int offset,int length){
            if(offset<0||length<0||offset>data.length-length||length%4!=0)throw new IllegalArgumentException();
            if(closed||failure!=null)return 0;
            int frames=Math.min(length/4,CAPACITY-size);
            for(int f=0;f<frames;f++)for(int c=0;c<2;c++){
                int i=offset+f*4+c*2;ring[((read+size+f)%CAPACITY)*2+c]=(short)((data[i]&255)|(data[i+1]<<8));
            }
            size+=frames;return frames*4;
        }
        public synchronized void pause(boolean value){paused=value;}
        public Exception failure(){return failure;}
        public synchronized long playedFrames(){return played;}
        public synchronized boolean drained(){return size==0&&spans.isEmpty();}
        private synchronized boolean ready(){return !closed&&!paused&&size>0;}
        private synchronized boolean active(){return !closed&&!paused;}
        private synchronized void mix(int[] sum,int frames,long deviceStart){
            if(closed||paused)return;
            int count=Math.min(frames,size);
            for(int f=0;f<count;f++)for(int c=0;c<2;c++)sum[f*2+c]+=ring[((read+f)%CAPACITY)*2+c];
            if(count>0){spans.addLast(new Span(deviceStart,consumed,count));consumed+=count;size-=count;read=(read+count)%CAPACITY;}
        }
        private synchronized void progress(long frame){
            while(!spans.isEmpty()){
                var span=spans.getFirst();long count=Math.clamp(frame-span.deviceStart,0,span.frames);
                played=Math.max(played,span.sourceStart+count);
                if(count<span.frames)break;spans.removeFirst();
            }
        }
        @Override public void close(){synchronized(this){closed=true;size=0;spans.clear();}remove(this);}
    }
    private void run(){
        Device device=null;
        try{
            device=factory.open();long written=0;boolean running=false;
            int[] sum=new int[BLOCK*2];byte[] output=new byte[BLOCK*4];
            while(true){
                List<Port> current;
                synchronized(this){
                    if(ports.isEmpty()){worker=null;return;}
                    current=List.copyOf(ports);
                }
                long played=device.played();for(var p:current)p.progress(played);
                boolean active=current.stream().anyMatch(Port::active);
                boolean ready=current.stream().anyMatch(Port::ready);
                if(!active){if(running){device.stop();running=false;}Thread.sleep(2);continue;}
                if(!running){device.start();running=true;}
                // A slow/empty producer does not hold up other sources. Let the device drain at EOF.
                if(!ready||device.available()<BLOCK*4){Thread.sleep(1);continue;}
                Arrays.fill(sum,0);for(var p:current)p.mix(sum,BLOCK,written);
                SpeakerLimiter.encode(sum,BLOCK,output);
                int offset=0;
                while(offset<output.length){
                    int n=device.write(output,offset,output.length-offset);
                    if(n<0||n%4!=0)throw new IllegalStateException("Invalid audio device write");
                    if(n==0){synchronized(this){if(ports.isEmpty()){worker=null;return;}}Thread.sleep(1);continue;}offset+=n;
                }
                written+=BLOCK;
            }
        }catch(Exception e){
            synchronized(this){for(var p:ports)p.failure=e;ports.clear();worker=null;}
        }finally{
            if(device!=null)try{device.close();}catch(Exception ignored){}
        }
    }
}
