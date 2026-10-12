package soke.musicdelay.client.speaker.audio;

import java.util.*;
import soke.musicdelay.client.speaker.audio.SpeakerPropagation.Point;

/** Small dry-air detours around a water obstacle, not a full diffraction solver. */
public final class SpeakerWaterBypass {
    private SpeakerWaterBypass() { }
    private static double length(List<Point> route){double result=0;for(int i=1;i<route.size();i++)result+=route.get(i-1).distance(route.get(i));return result;}
    public static SpeakerWater.Profile measure(SpeakerWater.Field fluid,SpeakerPropagation.Field solid,
                                               List<Point> route,SpeakerWater.Path directPath){
        var direct=SpeakerWater.profile(false,false,directPath);
        if(route.size()<2 || route.size()>8 || directPath.waterLength()<=0)return direct;
        double baseLength=length(route);
        if(baseLength<.01 || baseLength>96)return direct;
        Point axis=route.getLast().sub(route.getFirst());double n=axis.distance(new Point(0,0,0));
        if(n<.01)return direct;
        axis=axis.scale(1/n);
        Point u=Math.abs(axis.y())<.9?new Point(-axis.z(),0,axis.x()):new Point(0,axis.z(),-axis.y());
        u=u.scale(1/u.distance(new Point(0,0,0)));
        Point v=new Point(axis.y()*u.z()-axis.z()*u.y(),axis.z()*u.x()-axis.x()*u.z(),axis.x()*u.y()-axis.y()*u.x());
        double[] power=new double[3];for(int b=0;b<3;b++)power[b]=.2*Math.pow(direct.transmission().at(b),2);
        int remaining=2048;
        for(int probe=0;probe<8;probe++){
            double angle=probe*Math.PI/4;
            Point offset=u.scale(1.5*Math.cos(angle)).add(v.scale(1.5*Math.sin(angle)));
            var candidate=new ArrayList<Point>();candidate.add(route.getFirst());double walked=0;
            for(int i=1;i<route.size();i++){
                Point a=route.get(i-1),b=route.get(i);double segment=a.distance(b);
                candidate.add(a.add(b).scale(.5).add(offset.scale(Math.sin(Math.PI*(walked+segment*.5)/baseLength))));
                candidate.add(b);walked+=segment;
            }
            boolean clear=true;
            for(int i=1;i<candidate.size();i++){
                Point a=candidate.get(i-1),b=candidate.get(i);double distance=a.distance(b);
                // Each ray crosses fewer than the legacy DDA's 256 cells. Budget is conservative.
                int cost=(int)Math.ceil(distance*Math.sqrt(3))+4;
                if(cost>remaining || distance>120){clear=false;break;}remaining-=cost;
                if(SpeakerEnergy.cast(solid,a,b.sub(a),distance)!=null){clear=false;break;}
            }
            var bands=direct.transmission();
            if(clear){
                var path=SpeakerWater.measure(fluid,candidate,false,false);
                double extra=Math.max(0,length(candidate)-baseLength);
                double loss=SpeakerDistance.gain(baseLength+extra,1)/SpeakerDistance.gain(baseLength,1)*Math.exp(-.08*extra);
                bands=SpeakerWater.attenuation(false,false,path).scale(Math.min(1,loss));
            }
            // Fixed normalized weights: more probes cannot multiply the source's energy.
            // A blocked/long detour must not damage or amplify the original direct route.
            for(int b=0;b<3;b++)power[b]+=.1*Math.pow(Math.max(direct.transmission().at(b),bands.at(b)),2);
        }
        return new SpeakerWater.Profile(new SpeakerEnergy.Bands(Math.min(1,Math.sqrt(power[0])),Math.min(1,Math.sqrt(power[1])),Math.min(1,Math.sqrt(power[2]))),false,direct.crossesSurface(),false);
    }
}
