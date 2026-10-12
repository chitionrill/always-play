package soke.musicdelay.client.speaker.audio;

import java.util.*;

/** Finite multi-band impulse response with power normalization and peak protection. */
public final class SpeakerSpatialFilter {
    private final int channels,size;
    private final double rate,smooth,releaseAlpha;
    private final float[][] history;
    private final double[][] inputLow=new double[2][2],dry=new double[2][3];
    private final double[][] gains=new double[SpeakerEnergy.BINS][3],targets=new double[SpeakerEnergy.BINS][3];
    private final double[] delays=new double[SpeakerEnergy.BINS],next=new double[SpeakerEnergy.BINS],fades=new double[SpeakerEnergy.BINS];
    private final double[] direct=new double[3],total=new double[3];
    private final double[][] wet=new double[2][3];
    private final double[] left=new double[SpeakerEnergy.BINS],right=new double[SpeakerEnergy.BINS],wantLeft=new double[SpeakerEnergy.BINS],wantRight=new double[SpeakerEnergy.BINS];
    private final double lowAlpha,highAlpha;
    private int write;
    private double limiter=1;
    private final double[] outputFrame=new double[2];
    private boolean initialized;
    public SpeakerSpatialFilter(float rate,int channels){
        if(!Float.isFinite(rate)||rate<=0||channels<1||channels>2)throw new IllegalArgumentException();
        this.rate=rate;this.channels=channels;size=(int)Math.ceil(rate*3.2)+2;history=new float[3][size];
        smooth=-Math.expm1(-1/(rate*.10));releaseAlpha=-Math.expm1(-1/(rate*.08));lowAlpha=-Math.expm1(-2*Math.PI*350/rate);highAlpha=-Math.expm1(-2*Math.PI*3000/rate);
        Arrays.fill(left,Math.sqrt(.5));Arrays.fill(right,Math.sqrt(.5));
        Arrays.fill(delays,rate*.01);Arrays.fill(next,rate*.01);Arrays.fill(fades,1);
    }
    public int tailFrames(){
        double last=0;
        for(int i=0;i<gains.length;i++)if(Math.max(gains[i][0],Math.max(gains[i][1],gains[i][2]))>1e-5 || targets[i][0]>1e-5 || targets[i][1]>1e-5 || targets[i][2]>1e-5)last=Math.max(last,Math.max(delays[i],next[i])*1.04);
        if(last==0){for(var channel:inputLow)for(double value:channel)if(Math.abs(value)>.5)return (int)Math.ceil(rate*.03);return 0;}
        return (int)Math.ceil(last+rate*.03);
    }
    /** Produces spatial stereo directly: the caller must not collapse the output to mono. */
    public int process(byte[] pcm,int count,byte[] output,float gain,float cutoff,List<SpeakerPropagation.Reflection> response,SpeakerPcm.Levels from,SpeakerPcm.Levels to,boolean underwater){
        return process(pcm,count,output,SpeakerEnergy.direct(gain,cutoff),response,from,to,underwater);
    }
    public int process(byte[] pcm,int count,byte[] output,SpeakerEnergy.Bands wanted,List<SpeakerPropagation.Reflection> response,SpeakerPcm.Levels from,SpeakerPcm.Levels to,boolean underwater){
        if(count<0||count>pcm.length||count%(channels*2)!=0||output.length<count/(channels*2)*4||wanted.max()>1)throw new IllegalArgumentException();
        for(var band:targets)Arrays.fill(band,0);
        for(var tap:response){
            int slot=tap.slot();if(slot<0||slot>=targets.length||!Double.isFinite(tap.seconds())||tap.seconds()<0)throw new IllegalArgumentException();
            for(int band=0;band<3;band++)targets[slot][band]=tap.spectrum().at(band);
            double angle=(Math.clamp(tap.pan(),-1,1)*(underwater?.25:1)+1)*Math.PI/4;
            wantLeft[slot]=Math.cos(angle);wantRight[slot]=Math.sin(angle);
            if(!initialized){left[slot]=wantLeft[slot];right[slot]=wantRight[slot];}
            double wantedDelay=Math.clamp(tap.seconds()*rate,1,rate*SpeakerEnergy.MAX_SECONDS);
            if(fades[slot]>=1 && Math.abs(wantedDelay-next[slot])>.5){delays[slot]=next[slot];next[slot]=wantedDelay;fades[slot]=0;}
        }
        if(!initialized&&count>0){for(int b=0;b<3;b++)direct[b]=wanted.at(b);initialized=true;}
        for(int frame=0;frame<count;frame+=channels*2){
            for(int b=0;b<3;b++){direct[b]+=(wanted.at(b)-direct[b])*smooth;total[b]=direct[b]*direct[b];wet[0][b]=0;wet[1][b]=0;}
            for(int c=0;c<channels;c++){
                int i=frame+c*2;double sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));
                inputLow[c][0]+=lowAlpha*(sample-inputLow[c][0]);inputLow[c][1]+=highAlpha*(sample-inputLow[c][1]);
                dry[c][0]=inputLow[c][0];dry[c][1]=inputLow[c][1]-inputLow[c][0];dry[c][2]=sample-inputLow[c][1];
            }
            for(int b=0;b<3;b++)history[b][write]=(float)(channels==1?dry[0][b]:(dry[0][b]+dry[1][b])*.5);
            for(int slot=0;slot<gains.length;slot++){
                left[slot]+=(wantLeft[slot]-left[slot])*smooth;right[slot]+=(wantRight[slot]-right[slot])*smooth;
                boolean active=false;
                for(int b=0;b<3;b++){gains[slot][b]+=(targets[slot][b]-gains[slot][b])*smooth;if(gains[slot][b]>1e-7)active=true;total[b]+=gains[slot][b]*gains[slot][b];}
                fades[slot]=Math.min(1,fades[slot]+1/(rate*.08));if(!active)continue;
                for(int b=0;b<3;b++){
                    double value=read(b,next[slot]);
                    if(fades[slot]<1)value=value*fades[slot]+read(b,delays[slot])*(1-fades[slot]);
                    wet[0][b]+=value*gains[slot][b]*left[slot];wet[1][b]+=value*gains[slot][b]*right[slot];
                }
            }
            double t=(frame/(channels*2)+1.0)/(count/(channels*2));
            double level=from.gain()+(to.gain()-from.gain())*t;
            double pan=from.pan()+(to.pan()-from.pan())*t;
            double angle=(pan+1)*Math.PI/4,peak=0;
            for(int c=0;c<2;c++){
                double value=0,position=c==0?Math.cos(angle):Math.sin(angle);
                for(int b=0;b<3;b++){
                    double mono=channels==1?dry[0][b]:(dry[0][b]+dry[1][b])*.5;
                    value+=(mono*direct[b]*position+wet[c][b])/Math.sqrt(Math.max(1,total[b]));
                }
                outputFrame[c]=value;peak=Math.max(peak,Math.abs(value));
            }
            limiter=Math.min(limiter+(1-limiter)*releaseAlpha,peak>32768?32767/peak:1);
            for(int c=0;c<2;c++){
                int value=Math.clamp((int)Math.round(outputFrame[c]*limiter*level),-32768,32767),i=frame/(channels*2)*4+c*2;
                output[i]=(byte)value;output[i+1]=(byte)(value>>8);
            }
            if(++write==size)write=0;
        }
        return count/(channels*2)*4;
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
