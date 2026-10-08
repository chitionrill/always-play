package soke.musicdelay.client.speaker.audio;

import java.util.*;

/** Bounded geometric acoustics. All field queries run on the caller's world thread. */
public final class SpeakerPropagation {
    @FunctionalInterface public interface Field {
        double loss(double x,double y,double z);
        default double reflection(double x,double y,double z){return .55;}
    }
    public record Point(double x,double y,double z) {
        public double distance(Point b){return Math.sqrt((x-b.x)*(x-b.x)+(y-b.y)*(y-b.y)+(z-b.z)*(z-b.z));}
        Point add(Point b){return new Point(x+b.x,y+b.y,z+b.z);}
        Point scale(double v){return new Point(x*v,y*v,z*v);}
        Point sub(Point b){return new Point(x-b.x,y-b.y,z-b.z);}
        double dot(Point b){return x*b.x+y*b.y+z*b.z;}
    }
    /** Stable slot identifies a probe direction, avoiding randomly reassigned echo taps. */
    public record Reflection(int slot,double seconds,float gain,float cutoff) { }
    public record Result(double distance,float gain,float cutoff,Point arrival,List<Reflection> reflections) {
        public Result {reflections=List.copyOf(reflections);}
        public Result(double distance,float gain,float cutoff){this(distance,gain,cutoff,null,List.of());}
        public static final Result CLEAR=new Result(0,1,20000);
        public static final Result SILENT=new Result(0,0,20000);
    }
    private record Cell(int x,int y,int z) {
        Point point(){return new Point(x*.5+.25,y*.5+.25,z*.5+.25);}
    }
    private record Step(Cell cell,double cost,double priority) { }
    private record Segment(Point a,Point b) { }
    private static final int[][] DIRECTIONS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private final Field field;
    public SpeakerPropagation(Field field){this.field=Objects.requireNonNull(field);}
    public Result solve(Point source,Point listener,double range){var job=begin(source,listener,range);while(!job.advance(256,Long.MAX_VALUE)){}return job.result();}
    public Job begin(Point source,Point listener,double range){return new Job(source,listener,range);}
    private static double falloff(double length,double range){return length>=range || range<=0?0:Math.pow(1-length/range,2);}
    private static double score(Result result,double range){return result.gain*falloff(result.distance,range);}
    public final class Job {
        private final Point source,listener;
        private final double range;
        private final PriorityQueue<Step> queue=new PriorityQueue<>(Comparator.comparingDouble(Step::priority));
        private final Map<Cell,Double> best=new HashMap<>();
        private final Map<Cell,Point> parents=new HashMap<>();
        private final Map<Point,Point> links=new HashMap<>();
        private final Map<Segment,Double> traces=new HashMap<>();
        private final List<Reflection> reflections=new ArrayList<>();
        private Result result;
        private boolean initialized,pathDone,done;
        private int samples,visited,probe;
        private Job(Point source,Point listener,double range){this.source=source;this.listener=listener;this.range=range;}
        public double directDistance(){return source.distance(listener);}
        public boolean near(Point a,Point b,double r){return source.distance(a)<.75 && listener.distance(b)<1.5 && range==r;}
        public Result provisional(){return result;}
        public Result result(){if(!done)throw new IllegalStateException("Unfinished acoustic search");return result;}
        private double trace(Point a,Point b){
            var key=new Segment(a,b);var saved=traces.get(key);if(saved!=null)return saved;
            double length=a.distance(b),loss=0;int count=Math.max(1,(int)Math.ceil(length*16));
            for(int i=0;i<count;i++){
                if(samples++>=90000)return Double.POSITIVE_INFINITY;
                double t=(i+.5)/count;
                loss+=field.loss(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t)*length/count;
            }
            traces.put(key,loss);return loss;
        }
        private void initialize(){
            initialized=true;double length=directDistance();
            result=new Result(length,0,20000);
            if(range<=0 || length>=range){pathDone=true;done=true;return;}
            double loss=trace(source,listener);
            result=new Result(length,loss>=6?0:(float)Math.exp(-loss),loss<.0001?20000:(float)Math.max(250,16000*Math.exp(-loss*.8)),source,List.of());
            if(loss<.0001){pathDone=true;return;}
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
            float gain=(float)Math.exp(-.055*extra-.10*bend);
            float cutoff=(float)Math.max(1400,20000*Math.exp(-.10*extra-.38*bend));
            var around=new Result(length,gain,cutoff,simple.get(Math.max(0,simple.size()-2)),List.of());
            if(score(around,range)>score(result,range))result=around;
        }
        private void node(){
            var next=queue.poll();visited++;
            if(next.cost>best.getOrDefault(next.cell,Double.POSITIVE_INFINITY))return;
            var point=next.cell.point();
            // Exact listener connection prevents the wall/air cell boundary becoming a sound switch.
            if(point.distance(listener)<1.5 && trace(point,listener)==0){finishPath(point);pathDone=true;return;}
            for(var direction:DIRECTIONS){
                var cell=new Cell(next.cell.x+direction[0],next.cell.y+direction[1],next.cell.z+direction[2]);var target=cell.point();
                double cost=next.cost+.5;Point parent=point;
                if(cost+target.distance(listener)>=range || trace(point,target)>0)continue;
                var ancestor=parents.get(next.cell);
                if(ancestor!=null && trace(ancestor,target)==0){cost=next.cost-ancestor.distance(point)+ancestor.distance(target);parent=ancestor;}
                if(cost>=best.getOrDefault(cell,Double.POSITIVE_INFINITY))continue;
                best.put(cell,cost);parents.put(cell,parent);links.put(target,parent);
                queue.add(new Step(cell,cost,cost+target.distance(listener)));
            }
        }
        private void reflect(int slot){
            var axis=DIRECTIONS[slot];var outward=new Point(axis[0],axis[1],axis[2]);
            double hit=0;
            for(double distance=.125;distance<Math.min(range,12);distance+=.125){
                var sample=source.add(outward.scale(distance));if(samples++>=90000)return;
                if(field.loss(sample.x,sample.y,sample.z)>0){hit=distance;break;}
            }
            if(hit==0)return;
            double lo=Math.max(0,hit-.125),hi=hit;
            for(int i=0;i<5;i++){double mid=(lo+hi)*.5;var q=source.add(outward.scale(mid));
                if(field.loss(q.x,q.y,q.z)>0)hi=mid;else lo=mid;}
            var plane=source.add(outward.scale(lo));var image=source.add(outward.scale(2*lo));
            double denominator=listener.sub(image).dot(outward);if(Math.abs(denominator)<1e-6)return;
            double t=plane.sub(image).dot(outward)/denominator;if(t<=0 || t>=1)return;
            var bounce=image.add(listener.sub(image).scale(t));var inside=bounce.add(outward.scale(.02));
            if(field.loss(inside.x,inside.y,inside.z)<=0)return;
            var air=bounce.sub(outward.scale(.02));
            if(field.loss(air.x,air.y,air.z)>0 || trace(source,air)>0 || trace(air,listener)>0)return;
            double length=source.distance(bounce)+bounce.distance(listener);
            if(length>=range)return;
            double material=Math.clamp(field.reflection(inside.x,inside.y,inside.z),0,1);
            double ratio=falloff(length,range)/Math.max(.0001,falloff(result.distance,range));
            float gain=(float)Math.min(.16,.12*material*ratio);
            if(gain<.001)return;
            reflections.add(new Reflection(slot,Math.clamp((length-directDistance())/343,.001,.15),gain,(float)(1200+material*6500)));
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
                if(probe<6){reflect(probe++);continue;}
                result=new Result(result.distance,result.gain,result.cutoff,result.arrival,reflections);done=true;
            }
            return done;
        }
    }
}
