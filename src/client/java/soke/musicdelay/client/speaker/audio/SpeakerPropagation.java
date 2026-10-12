package soke.musicdelay.client.speaker.audio;

import java.util.*;

/** Bounded geometric acoustics. All field queries run on the caller's world thread. */
public final class SpeakerPropagation {
    @FunctionalInterface public interface Field {
        double loss(double x,double y,double z);
        default double reflection(double x,double y,double z){return .55;}
        default SpeakerEnergy.Voxel voxel(int x,int y,int z){
            double density=loss(x+.5,y+.5,z+.5);
            return density<=0?SpeakerEnergy.Voxel.AIR:new SpeakerEnergy.Voxel(
                    List.of(new SpeakerEnergy.Box(0,0,0,1,1,1)),new SpeakerEnergy.Material(density,reflection(x+.5,y+.5,z+.5)));
        }
    }
    public record Point(double x,double y,double z) {
        public double distance(Point b){return Math.sqrt((x-b.x)*(x-b.x)+(y-b.y)*(y-b.y)+(z-b.z)*(z-b.z));}
        Point add(Point b){return new Point(x+b.x,y+b.y,z+b.z);}
        Point scale(double v){return new Point(x*v,y*v,z*v);}
        Point sub(Point b){return new Point(x-b.x,y-b.y,z-b.z);}
        double dot(Point b){return x*b.x+y*b.y+z*b.z;}
    }
    /** Stable slot identifies a probe direction, avoiding randomly reassigned echo taps. */
    public record Reflection(int slot,double seconds,float gain,float cutoff,SpeakerEnergy.Bands spectrum,Point arrival,float pan) {
        public Reflection(int slot,double seconds,float gain,float cutoff,SpeakerEnergy.Bands spectrum){this(slot,seconds,gain,cutoff,spectrum,null,0);}
        public Reflection withPan(float value){return new Reflection(slot,seconds,gain,cutoff,spectrum,arrival,value);}
        public Reflection(int slot,double seconds,float gain,float cutoff){this(slot,seconds,gain,cutoff,new SpeakerEnergy.Bands(gain,gain,gain*Math.min(1,cutoff/12000.0)));}
    }
    public record Result(double distance,float gain,float cutoff,Point arrival,List<Reflection> reflections,SpeakerEnergy.Bands spectrum) {
        public Result {reflections=List.copyOf(reflections);Objects.requireNonNull(spectrum);}
        public Result(double distance,float gain,float cutoff,Point arrival,List<Reflection> reflections){
            this(distance,gain,cutoff,arrival,reflections,SpeakerEnergy.direct(gain,cutoff));
        }
        public static Result spectral(double distance,SpeakerEnergy.Bands bands,Point arrival,List<Reflection> reflections){
            var bounded=SpeakerEnergy.bounded(bands);
            return new Result(distance,(float)bounded.mid(),SpeakerEnergy.equivalentCutoff(bounded),arrival,reflections,bounded);
        }
        public Result(double distance,float gain,float cutoff){this(distance,gain,cutoff,null,List.of());}
        public static final Result CLEAR=new Result(0,1,20000);
        public static final Result SILENT=new Result(0,0,20000);
    }
    /** Source-to-aperture paths; endpoints radiate into visible air beyond the opening. */
    public record Aperture(List<List<Point>> paths,double area) {
        public Aperture { paths=paths.stream().map(List::copyOf).toList();
            if(!Double.isFinite(area) || area<0)throw new IllegalArgumentException("Invalid aperture area"); }
        public Aperture(List<List<Point>> paths){this(paths,paths.size()*.25);}
        public static final Aperture EMPTY=new Aperture(List.of());
    }
    private record Cell(int x,int y,int z) {
        Point point(){return new Point(x*.5+.25,y*.5+.25,z*.5+.25);}
    }
    private record Step(Cell cell,double cost,double priority) { }
    private record Segment(Point a,Point b) { }
    private static final int[][] DIRECTIONS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private final Field field;
    public SpeakerPropagation(Field field){this.field=Objects.requireNonNull(field);}
    public Result solve(Point source,Point listener,double strength){var job=begin(source,listener,strength);while(!job.advance(256,Long.MAX_VALUE)){}return job.result();}
    public Job begin(Point source,Point listener,double strength){return new Job(source,listener,strength);}
    private static double falloff(double length,double strength){return strength<=0?0:SpeakerDistance.gain(length,1);}
    private static double score(Result result,double strength){return result.gain*falloff(result.distance,strength);}
    public final class Job {
        private final Point source,listener;
        private final double strength;
        private final PriorityQueue<Step> queue=new PriorityQueue<>(Comparator.comparingDouble(Step::priority));
        private final Map<Cell,Double> best=new HashMap<>();
        private final Map<Cell,Point> parents=new HashMap<>();
        private final Map<Point,Point> links=new HashMap<>();
        private final Map<Segment,Double> traces=new HashMap<>();
        private final List<Reflection> reflections=new ArrayList<>();
        private Result result,direct;
        private long directProbeSignature;
        private double directEffectiveDepth;
        private String decision="direct";
        private Aperture aperture=Aperture.EMPTY;
        private final List<List<Point>> aperturePaths=new ArrayList<>();
        private Point apertureCenter;
        private List<Point> aperturePrefix;
        private int apertureAxis,apertureProbe;
        private boolean aperturePrepared,apertureDone;
        private List<Point> route=List.of();
        private boolean initialized,pathDone,done;
        private int samples,visited,probe;
        private Job(Point source,Point listener,double strength){this.source=source;this.listener=listener;this.strength=strength;}
        public double directDistance(){return source.distance(listener);}
        public boolean near(Point a,Point b,double r){return source.distance(a)<.75 && listener.distance(b)<6 && strength==r;}
        public boolean reflectionsNear(Point a,Point b){return source.distance(a)<.35 && listener.distance(b)<.35;}
        public Aperture aperture(){return aperture;}
        /** Report model decisions, not just the final mixed level. No extra world queries. */
        public String diagnostics(){
            return "directTransmission="+(direct==null?-1:direct.gain)+" directCutoff="+(direct==null?-1:direct.cutoff)
                    +" directBands="+(direct==null?SpeakerEnergy.Bands.ZERO:direct.spectrum)+" outputBands="+(result==null?SpeakerEnergy.Bands.ZERO:result.spectrum)+" directProbeSignature="+Long.toHexString(directProbeSignature)+" decision="+decision
                    +" effectiveBarrierDepth="+directEffectiveDepth+" routePoints="+route.size()+" aperturePatches="+aperture.paths.size()+" apertureArea="+aperture.area
                    +" traceSamples="+samples+" traceBudgetHit="+(samples>=90000);
        }
        public Result refresh(List<Point> previous,Aperture previousAperture){
            refresh(previous);
            if(result.gain<1)applyAperture(previousAperture);
            return result;
        }
        public List<Point> route(){return route;}
        /** Revalidate the known route against fresh geometry and the current listener. */
        public Result refresh(List<Point> previous){
            initialize();
            if(result.gain==1 || previous.size()<2 || previous.size()>32)return result;
            var updated=new ArrayList<Point>(previous);updated.set(0,source);updated.set(updated.size()-1,listener);
            // Keep the old endpoint as a corner if replacing it would cut through a door jamb.
            if(trace(updated.get(updated.size()-2),listener)>0)updated.add(updated.size()-1,previous.get(previous.size()-1));
            for(int i=1;i<updated.size();i++)if(trace(updated.get(i-1),updated.get(i))>0)return result;
            for(int i=1;i<updated.size()-1;) {
                if(trace(updated.get(i-1),updated.get(i+1))==0)updated.remove(i);else i++;
            }
            if(updated.size()>32)return result;
            links.clear();for(int i=1;i<updated.size()-1;i++)links.put(updated.get(i),updated.get(i-1));
            finishPath(updated.get(updated.size()-2));return result;
        }
        public Result provisional(){return result;}
        public Result result(){if(!done)throw new IllegalStateException("Unfinished acoustic search");return result;}
        private double trace(Point a,Point b){
            var key=new Segment(a,b);var saved=traces.get(key);if(saved!=null)return saved;
            double length=a.distance(b),loss=0;
            if(length<1e-10){traces.put(key,0.0);return 0;}
            // Integrate each crossed 1/16-block cell. Uniform distance samples can
            // miss a short intersection at a roof corner and invent an open route.
            double dx=b.x-a.x,dy=b.y-a.y,dz=b.z-a.z;
            double tx=nextPlane(a.x,dx),ty=nextPlane(a.y,dy),tz=nextPlane(a.z,dz);
            double sx=dx==0?Double.POSITIVE_INFINITY:1/(16*Math.abs(dx));
            double sy=dy==0?Double.POSITIVE_INFINITY:1/(16*Math.abs(dy));
            double sz=dz==0?Double.POSITIVE_INFINITY:1/(16*Math.abs(dz));
            double t=0,run=0,peak=0;
            while(t<1){
                double end=Math.min(1,Math.min(tx,Math.min(ty,tz))),mid=(t+end)*.5;
                double density=lossAt(a.x+dx*mid,a.y+dy*mid,a.z+dz*mid);
                if(!Double.isFinite(density))return Double.POSITIVE_INFINITY;
                if(density>0){run+=density*length*(end-t);peak=Math.max(peak,density);}
                else if(peak>0){double depth=surfaceLoss(run,peak);loss+=depth;run=0;peak=0;}
                boolean x=tx<=end+1e-12,y=ty<=end+1e-12,z=tz<=end+1e-12;
                // Reject zero-width visibility through touching corners. This tiny
                // geometric marker is not used as a wall's transmission coefficient.
                if(end<1 && (x?1:0)+(y?1:0)+(z?1:0)>1){
                    double px=a.x+dx*end,py=a.y+dy*end,pz=a.z+dz*end;
                    for(int axis=0;axis<3;axis++)for(int sign=-1;sign<=1;sign+=2){
                        double near=lossAt(px+(axis==0?sign*.00001:0),py+(axis==1?sign*.00001:0),pz+(axis==2?sign*.00001:0));
                        if(!Double.isFinite(near))return Double.POSITIVE_INFINITY;
                        if(near>0)loss+=1e-8;
                    }
                }
                if(x)tx+=sx;if(y)ty+=sy;if(z)tz+=sz;
                t=end;
            }
            if(peak>0){double depth=surfaceLoss(run,peak);loss+=depth;}
            traces.put(key,loss);return loss;
        }
        // Thin edges and panes still have a material interface. A bounded floor
        // avoids counting every grid cell as another surface and retains the
        // calibrated stronger isolation of doors and thick walls.
        private double surfaceLoss(double integrated,double peak){
            return Math.max(integrated,Math.min(.85,peak)*-Math.expm1(-integrated/.1));
        }
        private Result directBeam(double length){
            double beamDepth=0;
            directProbeSignature=1;
            // Fixed spherical footprint: no rotating frame and no stack of probes
            // sharing the same coordinate at an axis-aligned wall edge.
            // Integrate the effective barrier across a finite footprint before
            // solving its impedance. A grazing probe must not become a separate
            // full-band source through a closed stepped surface.
            for(int probe=0;probe<7;probe++){
                double z=probe==0?0:2*((probe-.5)/6.0)-1;
                double angle=probe*2.399963229728653+.37;
                double radius=probe==0?0:.2;
                Point shift=new Point(radius*Math.sqrt(1-z*z)*Math.cos(angle),radius*Math.sqrt(1-z*z)*Math.sin(angle),radius*z);
                double loss=trace(source.add(shift),listener.add(shift));
                directProbeSignature=31*directProbeSignature+Double.doubleToLongBits(loss);
                beamDepth+=loss;
            }
            directEffectiveDepth=beamDepth/7;
            return Result.spectral(length,SpeakerEnergy.transmissionAmplitudes(directEffectiveDepth),source,List.of());
        }
        private double nextPlane(double coordinate,double delta){
            if(delta==0)return Double.POSITIVE_INFINITY;
            double scaled=coordinate*16;
            double plane=delta>0?Math.floor(scaled)+1:Math.ceil(scaled)-1;
            return (plane/16-coordinate)/delta;
        }
        private void initialize(){
            initialized=true;double length=directDistance();
            result=new Result(length,0,20000);direct=result;
            if(strength<=0 || SpeakerDistance.gain(length,strength)<SpeakerDistance.PATH_FLOOR){pathDone=true;done=true;return;}
            double loss=trace(source,listener);
            result=directBeam(length);direct=result;
            if(loss==0){route=List.of(source,listener);pathDone=true;return;}
            // Connect the real source to surrounding air, not a single possibly obstructed centre.
            int bx=(int)Math.floor(source.x*2-.5),by=(int)Math.floor(source.y*2-.5),bz=(int)Math.floor(source.z*2-.5);
            for(int x=bx;x<=bx+1;x++)for(int y=by;y<=by+1;y++)for(int z=bz;z<=bz+1;z++){
                var cell=new Cell(x,y,z);var point=cell.point();double cost=source.distance(point);
                if(trace(source,point)>0)continue;
                best.put(cell,cost);parents.put(cell,source);links.put(point,source);
                queue.add(new Step(cell,cost,cost+point.distance(listener)));
            }
        }
        private void finishPath(Point end){
            var path=new ArrayList<Point>();path.add(listener);Point p=end;
            for(int i=0;i<128 && p!=null;i++) {path.add(p);if(p.equals(source))break;p=links.get(p);}
            if(!path.get(path.size()-1).equals(source))return;
            // Theta parent links already shortcut visible segments. Do not run a second
            // unbounded simplification pass at completion of an otherwise sliced search.
            Collections.reverse(path);var simple=path;
            double length=0,bend=0;
            for(int i=1;i<simple.size();i++){
                var delta=simple.get(i).sub(simple.get(i-1));double segment=delta.distance(new Point(0,0,0));length+=segment;
                if(i>1 && segment>1e-6){var before=simple.get(i-1).sub(simple.get(i-2));double d=before.distance(new Point(0,0,0));
                    if(d>1e-6)bend+=Math.acos(Math.clamp(before.dot(delta)/(d*segment),-1,1));}
            }
            double extra=Math.max(0,length-directDistance());
            var around=Result.spectral(length,pathSpectrum(extra,bend),simple.get(Math.max(0,simple.size()-2)),List.of());
            if(score(around,strength)>score(result,strength)){
                // The finite direct footprint can straddle an edge before its centre
                // becomes hidden. Introduce the alternative route continuously.
                double blend=1-direct.gain;
                double oldWeight=score(direct,strength)*(1-blend),newWeight=score(around,strength)*blend;
                double total=oldWeight+newWeight;
                var combined=direct.spectrum.scale(falloff(direct.distance,strength)*(1-blend)/Math.max(1e-9,falloff(length,strength))).add(around.spectrum.scale(blend));
                var candidate=Result.spectral(length,combined,around.arrival,List.of());
                if(score(candidate,strength)>score(result,strength)){result=candidate;route=List.copyOf(simple);decision="around";}
            }
        }
        private SpeakerEnergy.Bands pathSpectrum(double extra,double bend){
            return new SpeakerEnergy.Bands(Math.exp(-.035*extra-.07*bend),Math.exp(-.055*extra-.10*bend),Math.exp(-.10*extra-.38*bend));
        }
        private void node(){
            var next=queue.poll();visited++;
            if(next.cost>best.getOrDefault(next.cell,Double.POSITIVE_INFINITY))return;
            var point=next.cell.point();
            // Exact listener connection prevents the wall/air cell boundary becoming a sound switch.
            if(trace(point,listener)==0){finishPath(point);pathDone=true;return;}
            for(var direction:DIRECTIONS){
                var cell=new Cell(next.cell.x+direction[0],next.cell.y+direction[1],next.cell.z+direction[2]);var target=cell.point();
                double cost=next.cost+.5;Point parent=point;
                if(SpeakerDistance.gain(cost+target.distance(listener),strength)<SpeakerDistance.PATH_FLOOR || trace(point,target)>0)continue;
                var ancestor=parents.get(next.cell);
                if(ancestor!=null && trace(ancestor,target)==0){cost=next.cost-ancestor.distance(point)+ancestor.distance(target);parent=ancestor;}
                if(cost>=best.getOrDefault(cell,Double.POSITIVE_INFINITY))continue;
                best.put(cell,cost);parents.put(cell,parent);links.put(target,parent);
                queue.add(new Step(cell,cost,cost+target.distance(listener)));
            }
        }
        private double component(Point p,int axis){return axis==0?p.x:axis==1?p.y:p.z;}
        private Point offset(Point p,int axis,double value){return axis==0?new Point(p.x+value,p.y,p.z):axis==1?new Point(p.x,p.y+value,p.z):new Point(p.x,p.y,p.z+value);}
        private double lossAt(Point p){return lossAt(p.x,p.y,p.z);}
        private double lossAt(double x,double y,double z){samples++;return samples>=90000?Double.POSITIVE_INFINITY:field.loss(x,y,z);}
        /** Locate an exit plane from the adjacent solid's far face, using actual sampled geometry. */
        private void prepareAperture(){
            aperturePrepared=true;
            if(route.size()<3 || route.size()>32 || samples>=78000){apertureDone=true;return;}
            Point pivot=route.get(route.size()-2),before=route.get(route.size()-3),incoming=pivot.sub(before);
            apertureAxis=0;
            for(int axis=1;axis<3;axis++)if(Math.abs(component(incoming,axis))>Math.abs(component(incoming,apertureAxis)))apertureAxis=axis;
            double sign=Math.signum(component(incoming,apertureAxis));
            if(sign==0){apertureDone=true;return;}
            Point wall=null,section=pivot;
            // A path vertex may fall just before or after the wall. Search a short strip
            // along its incoming segment so the aperture does not depend on that grid choice.
            search: for(int step=0;step<=12;step++) {
                double shift=step==0?0:((step+1)/2)*.25*(step%2==1?-1:1);
                Point sectionCandidate=offset(pivot,apertureAxis,shift);
                for(double radius:new double[]{.25,.5,.75,1})
                    for(int axis=0;axis<3;axis++)if(axis!=apertureAxis)for(int direction:new int[]{-1,1}){
                        Point q=offset(sectionCandidate,axis,radius*direction);
                        if(lossAt(q)>0){wall=q;section=sectionCandidate;break search;}
                    }
            }
            if(wall==null){apertureDone=true;return;}
            double exit=-1;
            for(double d=.0625;d<=3;d+=.0625)if(lossAt(offset(wall,apertureAxis,sign*d))==0){exit=d;break;}
            if(exit<0){apertureDone=true;return;}
            apertureCenter=offset(section,apertureAxis,sign*(exit+.03125));
            aperturePrefix=List.copyOf(route.subList(0,route.size()-2));
        }
        /** Discover local aperture area independently of the listener's line of sight. */
        private void sampleAperture(){
            int a=(apertureAxis+1)%3,b=(apertureAxis+2)%3;
            int index=apertureProbe++;
            Point q=offset(offset(apertureCenter,a,(index%7-3)*.5),b,(index/7-3)*.5);
            if(samples<80000 && lossAt(q)==0 && trace(aperturePrefix.getLast(),q)==0){
                var path=new ArrayList<>(aperturePrefix);path.add(q);aperturePaths.add(List.copyOf(path));
            }
            if(apertureProbe>=49 || samples>=80000){
                var selected=new ArrayList<List<Point>>();int n=Math.min(9,aperturePaths.size());
                for(int i=0;i<n;i++)selected.add(aperturePaths.get((int)((i+.5)*aperturePaths.size()/n)));
                aperture=new Aperture(selected,aperturePaths.size()*.25);apertureDone=true;applyAperture(aperture);
            }
        }
        private void applyAperture(Aperture area){
            if(area.paths.isEmpty() || area.paths.size()>9 || SpeakerDistance.gain(directDistance(),strength)<SpeakerDistance.PATH_FLOOR)return;
            double energy=0,x=0,y=0,z=0;int supplied=0;
            var spectralPower=SpeakerEnergy.Bands.ZERO;
            for(var stored:area.paths){
                if(stored.size()<2 || stored.size()>32)continue;
                var path=new ArrayList<>(stored);path.set(0,source);
                boolean lit=true;double length=0,bend=0;
                for(int i=1;i<path.size();i++)if(trace(path.get(i-1),path.get(i))>0){lit=false;break;}
                if(!lit)continue;
                supplied++;
                Point portal=path.getLast();if(trace(portal,listener)>0)continue;
                path.add(listener);
                for(int i=1;i<path.size();i++){
                    Point delta=path.get(i).sub(path.get(i-1));double segment=delta.distance(new Point(0,0,0));length+=segment;
                    if(i>1 && segment>1e-6){Point last=path.get(i-1).sub(path.get(i-2));double d=last.distance(new Point(0,0,0));
                        if(d>1e-6)bend+=Math.acos(Math.clamp(last.dot(delta)/(d*segment),-1,1));}
                }
                double extra=Math.max(0,length-directDistance());
                double amplitude=Math.exp(-.055*extra-.10*bend)*falloff(length,strength),power=amplitude*amplitude;
                var spectrum=pathSpectrum(extra,bend).scale(falloff(length,strength));
                spectralPower=spectralPower.add(spectrum.multiply(spectrum));
                energy+=power;x+=portal.x*power;y+=portal.y*power;z+=portal.z*power;
            }
            if(energy<=0 || supplied==0)return;
            // Normalize: more samples never multiply source power. Hidden patches still consume their share.
            double base=falloff(directDistance(),strength);
            double norm=-Math.expm1(-area.area/.6)/area.paths.size();
            var spreadBands=new SpeakerEnergy.Bands(Math.sqrt(spectralPower.low()*norm),Math.sqrt(spectralPower.mid()*norm),Math.sqrt(spectralPower.high()*norm)).scale(1/Math.max(1e-9,base));
            var spread=Result.spectral(directDistance(),spreadBands,new Point(x/energy,y/energy,z/energy),List.of());
            if(score(spread,strength)>score(direct,strength)) {
                // Fade from geometric visibility to aperture radiation across the shadow boundary.
                double blend=1-direct.gain,old=score(result,strength),diffuse=score(spread,strength);
                double oldWeight=old*(1-blend),newWeight=diffuse*blend,total=oldWeight+newWeight;
                Point previous=result.arrival==null?source:result.arrival;
                Point arrival=previous.scale(oldWeight).add(spread.arrival.scale(newWeight)).scale(1/Math.max(1e-9,total));
                decision="aperture";
                var combined=result.spectrum.scale(falloff(result.distance,strength)*(1-blend)/Math.max(1e-9,base)).add(spread.spectrum.scale(blend));
                result=Result.spectral(directDistance(),combined,arrival,List.of());
            }
        }
        /** Small resumable batches, sharing the client's deadline. No worker reads world data. */
        public boolean advance(int nodes,long deadline){
            if(nodes<=0)throw new IllegalArgumentException();if(done)return true;
            if(System.nanoTime()>=deadline)return false;
            if(!initialized)initialize();
            int work=0;
            while(!done && work++<nodes && System.nanoTime()<deadline){
                if(!pathDone){
                    if(queue.isEmpty() || visited>=1800 || samples>=80000)pathDone=true;
                    else {node();continue;}
                }
                if(!aperturePrepared){prepareAperture();continue;}
                if(!apertureDone){sampleAperture();continue;}
                result=new Result(result.distance,result.gain,result.cutoff,result.arrival,reflections,result.spectrum);done=true;
            }
            return done;
        }
    }
}
