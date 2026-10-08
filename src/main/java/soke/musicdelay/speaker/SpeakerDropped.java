package soke.musicdelay.speaker;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;

/** A dropped shulker keeps its own contents; unrelated nearby drops are never grouped. */
public final class SpeakerDropped {
    private static final Map<ItemEntity,Integer> loaded=new IdentityHashMap<>();
    private SpeakerDropped() { }
    private static SpeakerStorage.View view(ItemEntity item) { return new SpeakerStorage.View(List.of(item.getItem())); }
    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server->loaded.clear());
        ServerEntityEvents.ENTITY_LOAD.register((entity,level)->{
            if(!level.getServer().isSingleplayer() || !(entity instanceof ItemEntity item) || !SpeakerStorage.relevant(item.getItem()))return;
            var view=view(item);var used=new HashSet<UUID>();
            for(var old:loaded.keySet())if(!old.isRemoved())for(var stack:view(old).items)SpeakerData.read(stack).ifPresent(s->used.add(s.id()));
            for(var stack:view.items) {
                var state=SpeakerRegistry.ensureState(stack);if(state.isEmpty())continue;
                var s=state.get();if(!used.add(s.id())) { s=s.copyForNewSpeaker();SpeakerData.write(stack,s);used.add(s.id()); }
            }
            view.commit();
            if(item.getAge()==0 && item.getOwner() instanceof ServerPlayer owner)SpeakerInventory.prepareDrop(owner,item);
            loaded.put(item,item.tickCount);publish(item,level);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity,level)->{if(entity instanceof ItemEntity item)loaded.remove(item);});
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(!server.isSingleplayer())return;
            var it=loaded.entrySet().iterator();
            while(it.hasNext()) {
                var entry=it.next();var item=entry.getKey();
                if(item.isRemoved() || !SpeakerStorage.relevant(item.getItem())) { it.remove();continue; }
                if(!(item.level() instanceof ServerLevel level) || entry.getValue()==item.tickCount)continue;
                entry.setValue(item.tickCount);var view=view(item);
                var valid=view.items.stream().filter(stack->SpeakerData.read(stack).isPresent()).toList();
                // Selection was resolved by the placed box or inventory before it became a drop.
                // Re-arbitrating with a different container's ranks could silence the surviving speaker.
                for(var stack:valid) {
                    var s=SpeakerData.read(stack).orElseThrow();
                    if(s.enabled() && !s.trackReference().isEmpty())s=s.withPlayback(s.trackReference(),Math.min(86_400_000L,s.positionMillis()+50));
                    SpeakerData.write(stack,s);
                }
                view.commit();if(level.getGameTime()%5==0)publish(item,level);
            }
        });
    }
    private static void publish(ItemEntity item,ServerLevel level) {
        var view=view(item);
        for(var stack:view.items)SpeakerData.read(stack).ifPresent(s->{
            var packet=new SpeakerPlayback.Snapshot(item.blockPosition(),s.id(),s.trackReference(),s.positionMillis(),
                    level.getGameTime()*50-s.positionMillis(),s.volume(),s.enabled(),false,item.getId(),false,item.blockPosition(),view.nested.contains(stack));
            for(var player:level.players())if(level.getServer().isSingleplayerOwner(player.nameAndId()) && player.distanceToSqr(item)<4096
                    && ServerPlayNetworking.canSend(player,SpeakerPlayback.Snapshot.TYPE))ServerPlayNetworking.send(player,packet);
        });
    }
    public static boolean finish(ServerPlayer player,SpeakerPlayback.End packet) {
        for(var item:loaded.keySet()) {
            if(item.isRemoved() || item.level()!=player.level() || player.distanceToSqr(item)>4096)continue;
            var view=view(item);
            for(var stack:view.items) {
                var state=SpeakerData.read(stack);if(state.isEmpty() || !state.get().id().equals(packet.id()))continue;
                var s=state.get();
                if(s.enabled() && s.trackReference().equals(packet.track()) && Math.abs(player.level().getGameTime()*50-s.positionMillis()-packet.epoch())<=150) {
                    SpeakerData.write(stack,s.withEnabled(false).withPlayback(s.trackReference(),0));view.commit();item.setItem(item.getItem().copy());publish(item,player.level());
                }
                return true;
            }
        }
        return false;
    }
}
