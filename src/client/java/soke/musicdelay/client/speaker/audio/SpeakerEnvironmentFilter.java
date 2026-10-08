package soke.musicdelay.client.speaker.audio;

import java.util.List;

/** Direct-path absorption plus six geometry-derived early reflection taps. */
public final class SpeakerEnvironmentFilter {
    private final int channels,size;
    private final double rate,smooth;
    private final double[] low=new double[2];
    private final double[][] history;
    private final double[][] reflectedLow=new double[6][2];
    private final double[] echoGain=new double[6],delay=new double[6],nextDelay=new double[6],fade=new double[6];
    private final double[] targetEcho=new double[6],wantedDelay=new double[6],echoAlpha=new double[6];
    private double gain,cutoff;
    private boolean initialized;
    private int write;
    public SpeakerEnvironmentFilter(float rate,int channels) {
        if(!Float.isFinite(rate) || rate<=0 || channels<1 || channels>2)throw new IllegalArgumentException();
        this.rate=rate;this.channels=channels;smooth=1-Math.exp(-1/(rate*.10));
        size=(int)Math.ceil(rate*.16)+2;history=new double[channels][size];
        java.util.Arrays.fill(delay,rate*.01);java.util.Arrays.fill(nextDelay,rate*.01);java.util.Arrays.fill(fade,1);
    }
    public void process(byte[] pcm,int count,float targetGain,float targetCutoff){process(pcm,count,targetGain,targetCutoff,List.of());}
    public void process(byte[] pcm,int count,float targetGain,float targetCutoff,List<SpeakerPropagation.Reflection> reflections) {
        if(count<0 || count>pcm.length || count%(channels*2)!=0 || !Float.isFinite(targetGain) || targetGain<0 || targetGain>1
                || !Float.isFinite(targetCutoff) || targetCutoff<200 || targetCutoff>20000)throw new IllegalArgumentException();
        java.util.Arrays.fill(targetEcho,0);
        for(int k=0;k<6;k++){wantedDelay[k]=nextDelay[k];echoAlpha[k]=1-Math.exp(-2*Math.PI*3000/rate);}
        for(var reflection:reflections){int k=reflection.slot();
            if(k<0 || k>=6 || !Double.isFinite(reflection.seconds()) || !Float.isFinite(reflection.gain()) || !Float.isFinite(reflection.cutoff()))throw new IllegalArgumentException();
            targetEcho[k]=Math.clamp(reflection.gain(),0,.16);
            wantedDelay[k]=Math.clamp(reflection.seconds()*rate,1,size-2);
            echoAlpha[k]=1-Math.exp(-2*Math.PI*Math.clamp(reflection.cutoff(),200,rate*.45)/rate);
        }
        if(!initialized && count>0){gain=targetGain;cutoff=targetCutoff;initialized=true;}
        for(int frame=0;frame<count;frame+=channels*2) {
            gain+=(targetGain-gain)*smooth;cutoff+=(targetCutoff-cutoff)*smooth;
            double alpha=1-Math.exp(-2*Math.PI*Math.min(cutoff,rate*.45)/rate);
            double wet=Math.clamp((20000-cutoff)/4000,0,1),total=gain;
            for(int k=0;k<6;k++){
                echoGain[k]+=(targetEcho[k]-echoGain[k])*smooth;total+=echoGain[k];
                if(fade[k]>=1 && Math.abs(wantedDelay[k]-nextDelay[k])>.5){delay[k]=nextDelay[k];nextDelay[k]=wantedDelay[k];fade[k]=0;}
                fade[k]=Math.min(1,fade[k]+1/(rate*.08));
            }
            for(int c=0;c<channels;c++) {
                int i=frame+c*2;double dry=(short)((pcm[i]&255)|(pcm[i+1]<<8));low[c]+=alpha*(dry-low[c]);history[c][write]=dry;
                double output=(dry+(low[c]-dry)*wet)*gain;
                for(int k=0;k<6;k++){
                    double echo=read(c,delay[k])*(1-fade[k])+read(c,nextDelay[k])*fade[k];
                    reflectedLow[k][c]+=echoAlpha[k]*(echo-reflectedLow[k][c]);output+=reflectedLow[k][c]*echoGain[k];
                }
                int value=Math.clamp((int)Math.round(output/Math.max(1,total)),-32768,32767);
                pcm[i]=(byte)value;pcm[i+1]=(byte)(value>>8);
            }
            if(++write==size)write=0;
        }
    }
    private double read(int channel,double offset){
        double position=write-offset;if(position<0)position+=size;
        int a=(int)position,b=a+1==size?0:a+1;double fraction=position-a;
        return history[channel][a]*(1-fraction)+history[channel][b]*fraction;
    }
}
