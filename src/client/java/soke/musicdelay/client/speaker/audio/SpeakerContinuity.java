package soke.musicdelay.client.speaker.audio;

/** Solo playback has no forward seek command. Epoch drift alone must not restart a decoder. */
public final class SpeakerContinuity {
    private SpeakerContinuity() { }
    public static boolean restart(String before,String after,long beforePosition,long afterPosition,boolean enabled){
        return !enabled || !before.equals(after) || afterPosition<beforePosition-150;
    }
}
