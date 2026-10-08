package soke.musicdelay.client.speaker.audio;

/** Per-voice PCM16LE filter. Short damped reflections, not a second delayed song.
 * Runs solely on the audio worker; parameters cross threads through immutable Control.
 */
public final class SpeakerChestFilter {
    private final int channels;
    private final double rate,smooth;
    private final float[][] delay;
    private final double[] low;
    private final int[] smallTaps,largeTaps;
    private int cursor;
    private double amount,open,size,metal,shellAmount;
    private boolean initialized;
    private final double[] body1=new double[2],body2=new double[2];
    private final double[] knock1=new double[2],knock2=new double[2];
    private final double[] ring1=new double[2],ring2=new double[2];
    public SpeakerChestFilter(float rate,int channels) {
        if(!Float.isFinite(rate) || rate<8000 || rate>384000 || (channels!=1 && channels!=2))throw new IllegalArgumentException("Unsupported PCM format");
        this.rate=rate;this.channels=channels;smooth=1-Math.exp(-1/(rate*.045));
        delay=new float[channels][(int)(rate*.05)+2];low=new double[channels];
        smallTaps=new int[]{(int)(rate*.007),(int)(rate*.013),(int)(rate*.021)};
        largeTaps=new int[]{(int)(rate*.011),(int)(rate*.023),(int)(rate*.037)};
    }
    public void process(byte[] pcm,int count,boolean chest,float openness,boolean doubleChest) {
        process(pcm,count,chest,openness,doubleChest,false);
    }
    public void process(byte[] pcm,int count,boolean chest,float openness,boolean doubleChest,boolean copper) {
        process(pcm,count,chest,openness,doubleChest,copper,false);
    }
    public void process(byte[] pcm,int count,boolean chest,float openness,boolean doubleChest,boolean copper,boolean shell) {
        if(count<0 || count>pcm.length || count%(channels*2)!=0)throw new IllegalArgumentException("Incomplete PCM frame");
        double target=Float.isFinite(openness)?Math.clamp(openness,0,1):0;
        if(!initialized && count>0) {
            amount=(chest || shell)?1:0;shellAmount=shell?1:0;open=target;size=doubleChest?1:0;metal=copper?1:0;initialized=true;
        }
        double radius=Math.exp(-1/(rate*.055)),resonance=2*radius*Math.cos(2*Math.PI*1450/rate);
        double bodyRadius=Math.exp(-1/(rate*.006));
        double bodyAngle=2*Math.PI*280/rate;
        double bodyResonance=2*bodyRadius*Math.cos(bodyAngle);
        double bodyFeed=(1-bodyRadius)*2*Math.sin(bodyAngle);
        double knockRadius=Math.exp(-1/(rate*.0018)),knockAngle=2*Math.PI*950/rate;
        double knockResonance=2*knockRadius*Math.cos(knockAngle);
        double knockFeed=(1-knockRadius)*2*Math.sin(knockAngle);
        int shellFrames=Math.max(1,(int)(rate*.0016));
        for(int offset=0;offset<count;offset+=channels*2) {
            amount+=(((chest || shell)?1:0)-amount)*smooth;open+=(target-open)*smooth;size+=((doubleChest?1:0)-size)*smooth;
            shellAmount+=((shell?1:0)-shellAmount)*smooth;
            metal+=((copper?1:0)-metal)*smooth;
            double cutoff=(950+open*8500)*(1-shellAmount)+(520+open*3700)*shellAmount;
            double alpha=1-Math.exp(-2*Math.PI*cutoff/rate);
            for(int c=0;c<channels;c++) {
                int i=offset+c*2;double dry=(short)((pcm[i]&255)|(pcm[i+1]<<8));
                low[c]+=alpha*(dry-low[c]);
                double early=0;
                for(int t=0;t<3;t++) {
                    double a=delay[c][(cursor-smallTaps[t]+delay[c].length)%delay[c].length];
                    double b=delay[c][(cursor-largeTaps[t]+delay[c].length)%delay[c].length];
                    early+=(a+(b-a)*size)*(t==0?.10:t==1?.055:.025);
                }
                delay[c][cursor]=(float)low[c];
                // Quiet, damped metallic resonance; stable poles and bounded feedback.
                double ring=(1-radius)*2*Math.sin(2*Math.PI*1450/rate)*low[c]+resonance*ring1[c]-radius*radius*ring2[c];
                ring2[c]=ring1[c];ring1[c]=ring;
                // Copper: audible short metallic slap plus a normalized resonant tail.
                double slap=delay[c][(cursor-(int)(rate*.043)+delay[c].length)%delay[c].length];
                double wood=low[c]*(.30+.50*open)+early*(.12+.88*open);
                double copperSound=low[c]*(.24+.34*open)+slap*(.055+.18*open)+ring*(.055+.105*open);
                double wet=wood+(copperSound-wood)*metal;
                double reflection=delay[c][(cursor-shellFrames+delay[c].length)%delay[c].length];
                double body=bodyFeed*low[c]+bodyResonance*body1[c]-bodyRadius*bodyRadius*body2[c];
                body2[c]=body1[c];body1[c]=body;
                double knock=knockFeed*low[c]+knockResonance*knock1[c]-knockRadius*knockRadius*knock2[c];
                knock2[c]=knock1[c];knock1[c]=knock;
                // Fixed, heavily damped cavity modes: a low thump and a dry nasal knock.
                // No modulation, long echo, or changes to the song's pitch/playback clock.
                double shellSound=low[c]*(.10+.20*open)+body*(.075+.35*open)
                        +knock*(.025+.20*open)-reflection*(.025+.06*open);
                wet+=(shellSound-wet)*shellAmount;
                int value=(int)Math.round(dry+(wet-dry)*amount);
                value=Math.clamp(value,-32768,32767);pcm[i]=(byte)value;pcm[i+1]=(byte)(value>>8);
            }
            cursor=(cursor+1)%delay[0].length;
        }
    }
}
