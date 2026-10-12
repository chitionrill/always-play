package soke.musicdelay.client.speaker.audio;

/** Client-thread lease: stale data requests authority, it does not itself mean deletion. */
public final class SpeakerSourceLease {
    private long seen,revision,missingSince=-1;
    private boolean pending;
    public void refresh(long tick){seen=tick;revision++;pending=false;}
    public boolean stale(long tick){return tick-seen>30;}
    public boolean missing(long tick,boolean present){
        if(present){missingSince=-1;return false;}
        if(missingSince<0)missingSince=tick;
        return tick-missingSince>6;
    }
    public long begin(){if(pending)return -1;pending=true;return revision;}
    public boolean finish(long token){if(!pending||token!=revision)return false;pending=false;return true;}
    public boolean pending(){return pending;}
}
