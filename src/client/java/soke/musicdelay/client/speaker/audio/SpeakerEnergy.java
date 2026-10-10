package soke.musicdelay.client.speaker.audio;

import java.util.*;
import soke.musicdelay.client.speaker.audio.SpeakerPropagation.Point;

/** Energy-domain transport. Coefficients are power fractions, never PCM amplitudes. */
public final class SpeakerEnergy {
    public static final int BINS=48,RAYS=48,MAX_BOUNCES=48;
    public static final double MAX_SECONDS=3,EPS=.002;
    public record Bands(double low,double mid,double high) {
        public Bands {if(!Double.isFinite(low)||!Double.isFinite(mid)||!Double.isFinite(high)||low<0||mid<0||high<0)throw new IllegalArgumentException("Invalid spectral energy");}
        public static final Bands ZERO=new Bands(0,0,0),ONE=new Bands(1,1,1);
        public Bands scale(double x){return new Bands(low*x,mid*x,high*x);}
        public Bands multiply(Bands b){return new Bands(low*b.low,mid*b.mid,high*b.high);}
        public Bands add(Bands b){return new Bands(low+b.low,mid+b.mid,high+b.high);}
        public double max(){return Math.max(low,Math.max(mid,high));}
        public double at(int i){return i==0?low:i==1?mid:high;}
    }
    public record Split(Bands transmitted,Bands reflected,Bands absorbed) { }
    public record Material(double density,double reflectable) {
        public Material {if(!Double.isFinite(density)||density<0||!Double.isFinite(reflectable)||reflectable<0||reflectable>1)throw new IllegalArgumentException();}
        public Split split(double thickness){
            if(!Double.isFinite(thickness)||thickness<0)throw new IllegalArgumentException();
            double[] t=new double[3],r=new double[3],a=new double[3];double[] factors={.55,1,1.8};
            for(int i=0;i<3;i++){
                t[i]=Math.exp(-2*density*factors[i]*thickness);
                double share=Math.clamp(reflectable*(i==0?1.08:i==2?.78:1),0,1);
                r[i]=(1-t[i])*share;a[i]=Math.max(0,1-t[i]-r[i]);
            }
            return new Split(new Bands(t[0],t[1],t[2]),new Bands(r[0],r[1],r[2]),new Bands(a[0],a[1],a[2]));
        }
    }
    public record Box(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) { }
    public record Voxel(List<Box> boxes,Material material) {
        public Voxel {boxes=List.copyOf(boxes);Objects.requireNonNull(material);}
        public static final Voxel AIR=new Voxel(List.of(),new Material(0,0));
    }
    public record Hit(Point point,Point normal,double distance,double thickness,Material material) { }
    private SpeakerEnergy() { }
    private static double axis(Point p,int i){return i==0?p.x():i==1?p.y():p.z();}
    private static Point vector(int axis,double sign){return new Point(axis==0?sign:0,axis==1?sign:0,axis==2?sign:0);}
    private static Point unit(Point p){double n=p.distance(new Point(0,0,0));return n<1e-12?new Point(1,0,0):p.scale(1/n);}
    /** Voxel DDA with exact box intersections. No sub-block marching or chunk loading. */
    public static Hit cast(SpeakerPropagation.Field field,Point origin,Point direction,double limit){
        if(limit<=0)return null;direction=unit(direction);
        int[] cell={(int)Math.floor(origin.x()),(int)Math.floor(origin.y()),(int)Math.floor(origin.z())};
        double[] next=new double[3],delta=new double[3];int[] sign=new int[3];
        for(int i=0;i<3;i++){
            double v=axis(direction,i);sign[i]=v>0?1:v<0?-1:0;
            delta[i]=sign[i]==0?Double.POSITIVE_INFINITY:Math.abs(1/v);
            next[i]=sign[i]==0?Double.POSITIVE_INFINITY:(cell[i]+(sign[i]>0?1:0)-axis(origin,i))/v;
        }
        double entered=0;
        for(int visited=0;visited<256 && entered<=limit;visited++){
            var voxel=field.voxel(cell[0],cell[1],cell[2]);Hit closest=null;
            for(var box:voxel.boxes){
                double lo=Double.NEGATIVE_INFINITY,hi=Double.POSITIVE_INFINITY;Point normal=new Point(0,1,0);boolean miss=false;
                double[] mins={box.minX+cell[0],box.minY+cell[1],box.minZ+cell[2]},maxs={box.maxX+cell[0],box.maxY+cell[1],box.maxZ+cell[2]};
                for(int i=0;i<3;i++){
                    double o=axis(origin,i),d=axis(direction,i);
                    if(Math.abs(d)<1e-12){if(o<mins[i]||o>maxs[i]){miss=true;break;}continue;}
                    double t1=(mins[i]-o)/d,t2=(maxs[i]-o)/d,n=-Math.signum(d);
                    if(t1>t2){double tmp=t1;t1=t2;t2=tmp;}
                    if(t1>lo){lo=t1;normal=vector(i,n);}hi=Math.min(hi,t2);
                    if(hi<lo){miss=true;break;}
                }
                if(miss||hi<=EPS*.1||Math.max(0,lo)>limit||hi<entered-1e-6)continue;
                double distance=Math.max(0,lo);
                if(closest==null||distance<closest.distance)closest=new Hit(origin.add(direction.scale(distance)),normal,distance,Math.max(EPS,hi-distance),voxel.material);
            }
            if(closest!=null)return closest;
            int axis=next[0]<=next[1]&&next[0]<=next[2]?0:next[1]<=next[2]?1:2;
            entered=next[axis];if(!Double.isFinite(entered))break;cell[axis]+=sign[axis];next[axis]+=delta[axis];
        }
        return null;
    }
    /** Adjacent blocks of the same material form one layer, not imaginary reflecting interfaces. */
    public static double materialDepth(SpeakerPropagation.Field field,Hit hit,Point direction){
        direction=unit(direction);double depth=hit.thickness;
        for(int i=0;i<64 && depth<32;i++){
            Point probe=hit.point.add(direction.scale(depth+EPS));
            Hit next=cast(field,probe,direction,EPS);
            if(next==null || next.distance>EPS*.5 || !next.material.equals(hit.material))break;
            depth+=EPS+next.thickness;
        }
        return depth;
    }
    public static double delay(int bin){return .001*Math.pow(MAX_SECONDS/.001,bin/(double)(BINS-1));}
    private static int bin(double seconds){return Math.clamp((int)Math.round(Math.log(Math.max(.001,seconds)/.001)/Math.log(MAX_SECONDS/.001)*(BINS-1)),0,BINS-1);}
    /** Direct absorption uses the same spectral density ratios as boundary transmission. */
    public static Bands direct(float gain,float cutoff){
        double g=Math.clamp(gain,0,1);
        return new Bands(Math.pow(g,.55),g*Math.min(1,cutoff/3500.0),Math.pow(g,1.8)*Math.min(1,cutoff/12000.0));
    }
    public static final class Transport {
        private record Packet(Point position,Point direction,Bands power,double length,int bounce,int seed,long sequence) { }
        private final SpeakerPropagation.Field field;
        private final Point source,listener;
        private final double[][] energy=new double[BINS][3];
        private final PriorityQueue<Packet> pending=new PriorityQueue<>(Comparator.<Packet>comparingDouble(p->-p.power.max()).thenComparingLong(Packet::sequence));
        private Bands absorbed=Bands.ZERO,unresolved=Bands.ZERO,escaped=Bands.ZERO,captured=Bands.ZERO;
        private Bands queued=Bands.ONE;
        private long sequence;
        private int events;
        private boolean done;
        private List<SpeakerPropagation.Reflection> response=List.of();
        public Transport(SpeakerPropagation.Field field,Point source,Point listener){
            this.field=field;this.source=source;this.listener=listener;
            for(int ray=0;ray<RAYS;ray++){
                double y=1-2*(ray+.5)/RAYS,phi=ray*Math.PI*(3-Math.sqrt(5)),r=Math.sqrt(Math.max(0,1-y*y));
                pending.add(new Packet(source,new Point(Math.cos(phi)*r,y,Math.sin(phi)*r),Bands.ONE.scale(1.0/RAYS),0,0,ray,sequence++));
            }
        }
        public boolean near(Point a,Point b){return source.distance(a)<3 && listener.distance(b)<6;}
        public Bands accounted(){Bands total=absorbed.add(unresolved).add(escaped).add(captured);for(var p:pending)total=total.add(p.power);return total;}
        public Bands unresolved(){return unresolved;}
        public int events(){return events;}
        public boolean done(){return done;}
        public List<SpeakerPropagation.Reflection> response(){if(!done)throw new IllegalStateException();return response;}
        private void enqueue(Point position,Point direction,Bands power,double length,int bounce,int seed){
            if(power.max()<1e-7 || bounce>=MAX_BOUNCES || length/343>=MAX_SECONDS || pending.size()>=2048){unresolved=unresolved.add(power);return;}
            pending.add(new Packet(position,direction,power,length,bounce,seed,sequence++));queued=queued.add(power);
        }
        private void step(){
            var state=pending.remove();events++;
            queued=new Bands(Math.max(0,queued.low-state.power.low),Math.max(0,queued.mid-state.power.mid),Math.max(0,queued.high-state.power.high));
            var hit=cast(field,state.position,state.direction,32);
            if(hit==null){escaped=escaped.add(state.power);return;}
            double length=state.length+hit.distance;
            Bands air=new Bands(Math.exp(-.0002*hit.distance),Math.exp(-.001*hit.distance),Math.exp(-.004*hit.distance));
            Bands packet=state.power.multiply(air);
            absorbed=absorbed.add(new Bands(state.power.low-packet.low,state.power.mid-packet.mid,state.power.high-packet.high));
            double depth=materialDepth(field,hit,state.direction);
            var split=hit.material.split(depth);
            absorbed=absorbed.add(packet.multiply(split.absorbed));
            // Both branches retain their actual fractions: transmission is not recycled into reflection.
            enqueue(hit.point.add(state.direction.scale(depth+EPS)),state.direction,
                    packet.multiply(split.transmitted),length+depth,state.bounce+1,state.seed);
            packet=packet.multiply(split.reflected);
            Point airPoint=hit.point.add(hit.normal.scale(EPS));Point toward=listener.sub(airPoint);double distance=toward.distance(new Point(0,0,0));
            double seconds=(length+distance-source.distance(listener))/343;
            if(seconds<=MAX_SECONDS && distance>EPS && cast(field,airPoint,toward,distance-EPS)==null){
                double cosine=Math.max(0,hit.normal.dot(unit(toward)));
                double fraction=Math.min(.35,.25*cosine/(1+distance*distance));
                Bands received=packet.scale(fraction);captured=captured.add(received);packet=packet.scale(1-fraction);
                int index=bin(seconds);for(int i=0;i<3;i++)energy[index][i]+=received.at(i);
            }
            Point reflected=state.direction.sub(hit.normal.scale(2*state.direction.dot(hit.normal)));
            double t=(state.seed*37+(state.bounce+1)*17)*2.399963229728653;
            Point scatter=unit(new Point(Math.cos(t),Math.sin(t*.73),Math.sin(t)));
            if(scatter.dot(hit.normal)<0)scatter=scatter.scale(-1);
            enqueue(airPoint,unit(reflected.scale(.65).add(scatter.scale(.35))),packet,length,state.bounce+1,state.seed);
        }
        public List<SpeakerPropagation.Reflection> preview(){return done?response:buildResponse();}
        private List<SpeakerPropagation.Reflection> buildResponse(){
            var taps=new ArrayList<SpeakerPropagation.Reflection>();
            double reference=.25/(1+Math.pow(source.distance(listener),2));
            for(int i=0;i<BINS;i++){
                var amplitude=new Bands(Math.sqrt(energy[i][0]/reference),Math.sqrt(energy[i][1]/reference),Math.sqrt(energy[i][2]/reference));
                if(amplitude.max()>1e-5)taps.add(new SpeakerPropagation.Reflection(i,delay(i),(float)amplitude.mid,20000,amplitude));
            }
            return List.copyOf(taps);
        }
        private void finish(){
            while(!pending.isEmpty())unresolved=unresolved.add(pending.remove().power);
            queued=Bands.ZERO;response=buildResponse();done=true;
        }
        public boolean advance(int budget,long deadline){
            if(budget<=0)throw new IllegalArgumentException();
            for(int i=0;!done&&i<budget&&System.nanoTime()<deadline;i++){
                if(pending.isEmpty()||queued.max()<.002||events>=4096){finish();break;}
                step();
            }
            return done;
        }
    }
}
