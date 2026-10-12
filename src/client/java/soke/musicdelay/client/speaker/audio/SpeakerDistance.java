package soke.musicdelay.client.speaker.audio;

/** Calibrated amplitude spreading, not a block-radius gate or a measured atmosphere model. */
public final class SpeakerDistance {
    public static final double WAKE=1e-4,SLEEP=5e-5,PATH_FLOOR=1e-5;
    private SpeakerDistance() { }
    public static double gain(double length,double strength){
        if(!Double.isFinite(length)||length<0||!Double.isFinite(strength)||strength<0)throw new IllegalArgumentException();
        // Finite near field, inverse-distance amplitude at long range, then medium loss.
        return strength/Math.hypot(1,length/4)*Math.exp(-.012*length);
    }
    public static boolean audible(double length,double strength,boolean alreadyPlaying){
        return gain(length,strength)>=(alreadyPlaying?SLEEP:WAKE);
    }
}
