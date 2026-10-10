package soke.musicdelay.client.speaker.audio;

import java.util.List;
import java.util.function.LongSupplier;

/** Cooperative time sharing. World-reading tasks still run exclusively on the client thread. */
public final class SpeakerWorkQueue {
    @FunctionalInterface public interface Task { void advance(long deadline); }
    private SpeakerWorkQueue() { }
    public static void service(List<Task> tasks,long deadline,LongSupplier clock){
        for(int i=0;i<tasks.size();i++){
            long now=clock.getAsLong(),remaining=deadline-now;
            if(remaining<=0)return;
            // Unused time is available to following tasks, but no task receives everybody's budget.
            long slice=remaining/(tasks.size()-i);
            if(slice<=0)return;
            tasks.get(i).advance(now+slice);
        }
    }
}
