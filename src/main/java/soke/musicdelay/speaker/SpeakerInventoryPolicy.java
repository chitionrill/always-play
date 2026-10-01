package soke.musicdelay.speaker;

import java.util.*;

/** Pure selection rule. Never turns an off speaker on. Equal ranks use input order. */
public final class SpeakerInventoryPolicy {
    public record Entry(UUID id, boolean main, boolean enabled, long order) { }
    private SpeakerInventoryPolicy() { }
    public static Set<UUID> select(List<Entry> entries) {
        Set<UUID> result=new HashSet<>();
        for(var e:entries) if(e.main && e.enabled) result.add(e.id);
        if(!result.isEmpty()) return Set.copyOf(result);
        Entry last=null;
        for(var e:entries) if(last==null || e.order>=last.order) last=e;
        if(last!=null && last.enabled) result.add(last.id);
        return Set.copyOf(result);
    }
}
