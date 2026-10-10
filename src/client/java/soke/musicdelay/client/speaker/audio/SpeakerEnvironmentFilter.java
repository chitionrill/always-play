package soke.musicdelay.client.speaker.audio;

import java.util.*;

/** Finite multi-band impulse response with power normalization and peak protection. */
public final class SpeakerEnvironmentFilter {
    private final int channels,size;
    private final double rate,smooth,releaseAlpha;
    private final float[][] history;
    private final double[][] inputLow=new double[2][2],dry=new double[2][3];
    private final double[][] gains=new double[SpeakerEnergy.BINS][3],targets=new double[SpeakerEnergy.BINS][3];
    private final double[] delays=new double[SpeakerEnergy.BINS],next=new double[SpeakerEnergy.BINS],fades=new double[SpeakerEnergy.BINS];
    private final double[] direct=new double[3],wet=new double[3],total=new double[3];
    private final double lowAlpha,highAlpha;
    private int write;
    private double limiter=1;
    private final double[] outputFrame=new double[2];
    private boolean initialized;
    public SpeakerEnvironmentFilter(float rate,int channels){
        if(!Float.isFinite(rate)||rate<=0||channels<1||channels>2)throw new IllegalArgumentException();
        this.rate=rate;this.channels=channels;size=(int)Math.ceil(rate*3.2)+2;history=new float[3][size];
        smooth=-Math.expm1(-1/(rate*.10));releaseAlpha=-Math.expm1(-1/(rate*.08));lowAlpha=-Math.expm1(-2*Math.PI*350/rate);highAlpha=-Math.expm1(-2*Math.PI*3000/rate);
        Arrays.fill(delays,rate*.01);Arrays.fill(next,rate*.01);Arrays.fill(fades,1);
    }
    public int tailFrames(){
        double last=0;
        for(int i=0;i<gains.length;i++)if(Math.max(gains[i][0],Math.max(gains[i][1],gains[i][2]))>1e-5 || targets[i][0]>1e-5 || targets[i][1]>1e-5 || targets[i][2]>1e-5)last=Math.max(last,Math.max(delays[i],next[i])*1.04);
        if(last==0){for(var channel:inputLow)for(double value:channel)if(Math.abs(value)>.5)return (int)Math.ceil(rate*.03);return 0;}
        return (int)Math.ceil(last+rate*.03);
    }
    public void process(byte[] pcm,int count,float gain,float cutoff){process(pcm,count,gain,cutoff,List.of());}
    public void process(byte[] pcm,int count,float gain,float cutoff,List<SpeakerPropagation.Reflection> response){
        if(count<0||count>pcm.length||count%(channels*2)!=0||!Float.isFinite(gain)||gain<0||gain>1||!Float.isFinite(cutoff)||cutoff<200||cutoff>20000)throw new IllegalArgumentException();
        var wanted=SpeakerEnergy.direct(gain,cutoff);
        for(var band:targets)Arrays.fill(band,0);
        for(var tap:response){
            int slot=tap.slot();if(slot<0||slot>=targets.length||!Double.isFinite(tap.seconds())||tap.seconds()<0)throw new IllegalArgumentException();
            for(int band=0;band<3;band++)targets[slot][band]=tap.spectrum().at(band);
            double wantedDelay=Math.clamp(tap.seconds()*rate,1,rate*SpeakerEnergy.MAX_SECONDS);
            if(fades[slot]>=1 && Math.abs(wantedDelay-next[slot])>.5){delays[slot]=next[slot];next[slot]=wantedDelay;fades[slot]=0;}
        }
        if(!initialized&&count>0){for(int b=0;b<3;b++)direct[b]=wanted.at(b);initialized=true;}
        for(int frame=0;frame<count;frame+=channels*2){
            for(int b=0;b<3;b++){direct[b]+=(wanted.at(b)-direct[b])*smooth;total[b]=direct[b]*direct[b];wet[b]=0;}
            for(int c=0;c<channels;c++){
                int i=frame+c*2;double sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));
                inputLow[c][0]+=lowAlpha*(sample-inputLow[c][0]);inputLow[c][1]+=highAlpha*(sample-inputLow[c][1]);
                dry[c][0]=inputLow[c][0];dry[c][1]=inputLow[c][1]-inputLow[c][0];dry[c][2]=sample-inputLow[c][1];
            }
            for(int b=0;b<3;b++)history[b][write]=(float)(channels==1?dry[0][b]:(dry[0][b]+dry[1][b])*.5);
            for(int slot=0;slot<gains.length;slot++){
                boolean active=false;
                for(int b=0;b<3;b++){gains[slot][b]+=(targets[slot][b]-gains[slot][b])*smooth;if(gains[slot][b]>1e-7)active=true;total[b]+=gains[slot][b]*gains[slot][b];}
                fades[slot]=Math.min(1,fades[slot]+1/(rate*.08));if(!active)continue;
                for(int b=0;b<3;b++){
                    double value=read(b,next[slot]);
                    if(fades[slot]<1)value=value*fades[slot]+read(b,delays[slot])*(1-fades[slot]);
                    wet[b]+=value*gains[slot][b];
                }
            }
            double peak=0;
            for(int c=0;c<channels;c++){
                double output=0;
                // Normalize power, not the sum of all delayed amplitudes: dense tails must not
                // make the direct sound collapse. Local constructive peaks are handled below.
                for(int b=0;b<3;b++)output+=(dry[c][b]*direct[b]+wet[b])/Math.sqrt(Math.max(1,total[b]));
                outputFrame[c]=output;peak=Math.max(peak,Math.abs(output));
            }
            limiter=Math.min(limiter+(1-limiter)*releaseAlpha,peak>32768?32767/peak:1);
            for(int c=0;c<channels;c++){
                int value=Math.clamp((int)Math.round(outputFrame[c]*limiter),-32768,32767),i=frame+c*2;
                pcm[i]=(byte)value;pcm[i+1]=(byte)(value>>8);
            }
            if(++write==size)write=0;
        }
    }
    private double read(int band,double delay){
        // Spread only within each measured delay group, without inventing a feedback decay time.
        return sample(band,delay*.96)*.25+sample(band,delay)*.5+sample(band,delay*1.04)*.25;
    }
    private double sample(int band,double delay){
        double position=write-delay;if(position<0)position+=size;
        int a=(int)position,b=a+1==size?0:a+1;double fraction=position-a;
        return history[band][a]*(1-fraction)+history[band][b]*fraction;
    }
}
