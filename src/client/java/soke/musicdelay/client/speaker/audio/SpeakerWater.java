package soke.musicdelay.client.speaker.audio;

import java.util.*;
import soke.musicdelay.client.speaker.audio.SpeakerPropagation.Point;

/** Bounded water-path sampling and perceptual attenuation; coefficients are amplitude ratios. */
public final class SpeakerWater {
    @FunctionalInterface public interface Field { boolean water(double x,double y,double z); }
    public record Path(double waterLength,int boundaries) { }
    public record Profile(SpeakerEnergy.Bands transmission,boolean listenerWet,boolean crossesSurface) {
        public static final Profile DRY=new Profile(SpeakerEnergy.Bands.ONE,false,false);
        public Profile(SpeakerEnergy.Bands transmission,boolean listenerWet){this(transmission,listenerWet,false);}
        public Profile { Objects.requireNonNull(transmission); if(transmission.max()>1)throw new IllegalArgumentException(); }
        public SpeakerPcm.Levels spatial(SpeakerPcm.Levels levels){
            return new SpeakerPcm.Levels(levels.gain(),levels.pan()*(listenerWet?.25f:1f));
        }
    }
    private SpeakerWater() { }
    public static Profile profile(boolean sourceWet,boolean listenerWet,Path path){
        return new Profile(attenuation(sourceWet,listenerWet,path),listenerWet,path.boundaries()>0);
    }
    public static Path measure(Field field,List<Point> route,boolean sourceWet,boolean listenerWet){
        if(route.size()<2 || route.size()>34)return new Path(0,sourceWet==listenerWet?0:1);
        double total=0;for(int i=1;i<route.size();i++)total+=route.get(i-1).distance(route.get(i));
        // Uniform arc-length samples: fixed cost regardless of how many vertices describe a path.
        int count=Math.clamp((int)Math.ceil(total*4),1,128),segment=1,boundaries=0;
        double length=0,base=0;boolean previous=sourceWet;
        for(int i=0;i<count;i++){
            double along=total*(i+.5)/count;
            while(segment<route.size()-1 && base+route.get(segment-1).distance(route.get(segment))<along){base+=route.get(segment-1).distance(route.get(segment));segment++;}
            Point a=route.get(segment-1),b=route.get(segment);double d=a.distance(b),t=d<1e-9?0:Math.clamp((along-base)/d,0,1);
            boolean wet=field.water(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t,a.z()+(b.z()-a.z())*t);
            if(total-along<.08)wet=listenerWet;
            if(wet!=previous)boundaries++;previous=wet;
            if(wet)length+=total/count;
        }
        if(previous!=listenerWet)boundaries++;
        return new Path(length,boundaries);
    }
    public static SpeakerEnergy.Bands attenuation(boolean sourceWet,boolean listenerWet,Path path){
        if(!Double.isFinite(path.waterLength)||path.waterLength<0||path.boundaries<0)throw new IllegalArgumentException();
        int boundaries=Math.min(8,path.boundaries);
        // Interface transmission and source coupling are separate from the listener's ears.
        // Audibility-oriented amplitude ratios, NOT impedance measurements or pressure dB.
        // At game distances water absorption of audible frequencies is small.
        return new SpeakerEnergy.Bands(
                (sourceWet?.96:1)*Math.pow(.62,boundaries)*Math.exp(-.0002*path.waterLength),
                (sourceWet?.96:1)*Math.pow(.54,boundaries)*Math.exp(-.0005*path.waterLength),
                (sourceWet?.96:1)*Math.pow(.42,boundaries)*Math.exp(-.001*path.waterLength));
    }
    /** Post-environment filter: direct sound and its existing tail cross the surface together. */
    public static final class Filter {
        private final int channels;
        private final double lowAlpha,highAlpha,smooth;
        private final double[][] low=new double[2][2];
        private final double[] gains=new double[3];
        private boolean initialized;
        private double immersion,surface;
        private final Biquad[] body=new Biquad[2], ceiling=new Biquad[2];
        private final Biquad[] throughToWater=new Biquad[2], throughToAir=new Biquad[2], surfaceBody=new Biquad[2];
        public Filter(float rate,int channels){
            if(!Float.isFinite(rate)||rate<=0||channels<1||channels>2)throw new IllegalArgumentException();
            for(int c=0;c<channels;c++){
                throughToWater[c]=new Biquad(rate,Math.min(650,rate*.2),.707,false);
                throughToAir[c]=new Biquad(rate,Math.min(1200,rate*.25),.707,false);
                surfaceBody[c]=new Biquad(rate,Math.min(380,rate*.12),.65,true);
                body[c]=new Biquad(rate,Math.min(550,rate*.15),.75,true);
                ceiling[c]=new Biquad(rate,Math.min(1900,rate*.3),.707,false);
            }
            this.channels=channels;lowAlpha=-Math.expm1(-2*Math.PI*Math.min(350,rate*.4)/rate);
            highAlpha=-Math.expm1(-2*Math.PI*Math.min(3000,rate*.45)/rate);smooth=-Math.expm1(-1/(rate*.08));
        }
        public void process(byte[] pcm,int count,SpeakerEnergy.Bands target){process(pcm,count,new Profile(target,false));}
        public void process(byte[] pcm,int count,Profile profile){
            SpeakerEnergy.Bands target=profile.transmission();
            double wet=profile.listenerWet()?1:0,crossing=profile.crossesSurface()?1:0;
            if(count<0||count>pcm.length||count%(channels*2)!=0||target.max()>1)throw new IllegalArgumentException();
            if(!initialized&&count>0){for(int b=0;b<3;b++)gains[b]=target.at(b);initialized=true;immersion=wet;surface=crossing;}
            for(int frame=0;frame<count;frame+=channels*2){
                immersion+=(wet-immersion)*smooth;
                surface+=(crossing-surface)*smooth;
                for(int b=0;b<3;b++)gains[b]+=(target.at(b)-gains[b])*smooth;
                for(int c=0;c<channels;c++){
                    int i=frame+c*2;double sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));
                    low[c][0]+=lowAlpha*(sample-low[c][0]);low[c][1]+=highAlpha*(sample-low[c][1]);
                    double value=low[c][0]*gains[0]+(low[c][1]-low[c][0])*gains[1]+(sample-low[c][1])*gains[2];
                    // Broad middle-frequency body, subdued brilliance and less sub-bass.
                    // The convex mix avoids a resonant gain boost or an invented echo loop.
                    double submerged=.28*ceiling[c].step(value)+.72*body[c].step(value);
                    // Art-directed surface colour, distinct from the fully submerged hearing profile.
                    // These are not measured interface transfer functions. No fake echo or modulation.
                    // Keep every branch warm so crossings never start with empty filter memory.
                    double core=surfaceBody[c].step(value);
                    double entering=.80*throughToWater[c].step(value)+.20*core;
                    double leaving=.65*throughToAir[c].step(value)+.35*core;
                    double ordinary=value+(submerged-value)*immersion;
                    double crossed=leaving+(entering-leaving)*immersion;
                    value=ordinary+(crossed-ordinary)*surface;
                    int output=Math.clamp((int)Math.round(value),-32768,32767);pcm[i]=(byte)output;pcm[i+1]=(byte)(output>>8);
                }
            }
        }
    }
    /** Constant-peak bandpass / Butterworth lowpass, transposed direct form II. */
    private static final class Biquad {
        private final double b0,b1,b2,a1,a2;
        private double z1,z2;
        Biquad(double rate,double frequency,double q,boolean bandpass){
            double w=2*Math.PI*frequency/rate,cos=Math.cos(w),alpha=Math.sin(w)/(2*q),a0=1+alpha;
            b0=(bandpass?alpha:(1-cos)/2)/a0;
            b1=(bandpass?0:1-cos)/a0;
            b2=(bandpass?-alpha:(1-cos)/2)/a0;
            a1=-2*cos/a0;a2=(1-alpha)/a0;
        }
        double step(double x){double y=b0*x+z1;z1=b1*x-a1*y+z2;z2=b2*x-a2*y;return y;}
    }

}
