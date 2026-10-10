package soke.musicdelay.client.speaker.audio;

/** Stereo-linked, memoryless soft knee on the summed PCM. Unity below 90% full scale. */
public final class SpeakerLimiter {
    private static final double KNEE=32767*.9,ROOM=32767-KNEE;
    private SpeakerLimiter() { }
    public static void encode(int[] sum,int frames,byte[] output){
        if(frames<0 || frames>sum.length/2 || frames>output.length/4)throw new IllegalArgumentException();
        for(int f=0;f<frames;f++){
            double peak=Math.max(Math.abs((double)sum[f*2]),Math.abs((double)sum[f*2+1]));
            double gain=peak<=KNEE?1:(KNEE+ROOM*(-Math.expm1(-(peak-KNEE)/ROOM)))/peak;
            for(int c=0;c<2;c++){
                int value=Math.clamp((int)Math.round(sum[f*2+c]*gain),-32767,32767),i=f*4+c*2;
                output[i]=(byte)value;output[i+1]=(byte)(value>>8);
            }
        }
    }
}
