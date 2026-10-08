package soke.musicdelay.client.speaker.audio;

/** Audible echoes with occasional short held fragments; lid attenuation is independent of the effect clock. */
public final class SpeakerEnderFilter {
    private static final double[] GAPS={7,11,5,9};
    private static final double[] GRAINS={.09,.12,.075,.105};
    private final int channels,length,ramp;
    private final double rate,smooth,echoAlpha,lidAlpha;
    private final float[][] history,echo;
    private final double[] echoLow=new double[2],lidLow=new double[2];
    private final int[] taps;
    private int cursor,episode,grainLength,grainStart,eventFrame=-1;
    private long untilEvent;
    private double blend,open;
    private boolean initialized;

    public SpeakerEnderFilter(float rate,int channels) {
        if(!Float.isFinite(rate) || rate<8000 || rate>384000 || channels<1 || channels>2)throw new IllegalArgumentException("Unsupported PCM format");
        this.rate=rate;this.channels=channels;length=(int)(rate*1.1)+2;ramp=Math.max(1,(int)(rate*.004));
        smooth=1-Math.exp(-1/(rate*.045));echoAlpha=1-Math.exp(-2*Math.PI*3400/rate);lidAlpha=1-Math.exp(-2*Math.PI*950/rate);
        history=new float[channels][length];echo=new float[channels][length];
        taps=new int[]{(int)(rate*.32),(int)(rate*.64),(int)(rate*.96)};
        untilEvent=(long)(rate*GAPS[0]);
    }
    public void process(byte[] pcm,int count,boolean active) { process(pcm,count,active,1); }
    public void process(byte[] pcm,int count,boolean active,float openness) {
        if(count<0 || count>pcm.length || count%(channels*2)!=0)throw new IllegalArgumentException("Incomplete PCM frame");
        double target=Float.isFinite(openness)?Math.clamp(openness,0,1):0;
        if(!initialized && count>0) {blend=active?1:0;open=target;initialized=true;}
        for(int frame=0;frame<count;frame+=channels*2) {
            blend+=((active?1:0)-blend)*smooth;open+=(target-open)*smooth;
            if(!active) {eventFrame=-1;episode=0;untilEvent=(long)(rate*GAPS[0]);}
            else if(eventFrame<0 && untilEvent--<=0) {
                grainLength=(int)(rate*GRAINS[episode]);grainStart=(cursor-grainLength+length)%length;eventFrame=0;
            }
            int phase=eventFrame<0?0:eventFrame%grainLength;
            // Each loop crossfades through the live signal at its seam; no hard sample discontinuity.
            double hold=eventFrame<0?0:.85*Math.clamp(Math.min(phase,grainLength-1-phase)/(double)ramp,0,1);
            for(int c=0;c<channels;c++) {
                int i=frame+c*2;double dry=(short)((pcm[i]&255)|(pcm[i+1]<<8));history[c][cursor]=(float)dry;
                double repeated=hold==0?dry:history[c][(grainStart+phase)%length];
                double signal=dry+(repeated-dry)*hold;
                echoLow[c]+=echoAlpha*(signal-echoLow[c]);echo[c][cursor]=(float)echoLow[c];
                double processed=.62*signal+.25*echo[c][(cursor-taps[0]+length)%length]
                        +.10*echo[c][(cursor-taps[1]+length)%length]+.03*echo[c][(cursor-taps[2]+length)%length];
                lidLow[c]+=lidAlpha*(processed-lidLow[c]);
                processed=(open*processed+(1-open)*lidLow[c])*(.32+.68*open);
                int value=Math.clamp((int)Math.round(dry+(processed-dry)*blend),-32768,32767);
                pcm[i]=(byte)value;pcm[i+1]=(byte)(value>>8);
            }
            if(eventFrame>=0 && ++eventFrame>=grainLength*3) {
                eventFrame=-1;episode=(episode+1)%GAPS.length;untilEvent=(long)(rate*GAPS[episode]);
            }
            cursor=(cursor+1)%length;
        }
    }
}
