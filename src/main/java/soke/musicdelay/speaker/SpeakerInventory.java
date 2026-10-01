package soke.musicdelay.speaker;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

/** Server-owned inventory arbitration; rank is preserved on the item across world restarts. */
public final class SpeakerInventory {
    private static final String ORDER="always_play_inventory_order";
    private static final Map<UUID,Set<UUID>> previous=new HashMap<>();
    private SpeakerInventory() { }
    private static long order(ItemStack item) {
        return item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getLong(ORDER).orElse(0L);
    }
    private static void order(ItemStack item,long value) {
        var tag=item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        tag.putLong(ORDER,value);item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
    }
    public static void promote(ServerPlayer player,ItemStack item) {
        long max=0;
        for(int i=0;i<player.getInventory().getContainerSize();i++) max=Math.max(max,order(player.getInventory().getItem(i)));
        if(max==Long.MAX_VALUE) {
            // Compact corrupt/externally edited ranks before incrementing them.
            List<ItemStack> items=new ArrayList<>();
            for(int i=0;i<player.getInventory().getContainerSize();i++) {
                var s=player.getInventory().getItem(i);if(s.is(SpeakerRegistry.ITEM))items.add(s);
            }
            items.sort(Comparator.comparingLong(SpeakerInventory::order));max=0;
            for(var s:items)order(s,++max);
        }
        order(item,max+1);
    }
    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server->previous.clear());
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(!server.isSingleplayer())return;
            Set<UUID> connected=new HashSet<>();
            for(var player:server.getPlayerList().getPlayers()) if(server.isSingleplayerOwner(player.nameAndId())) {
                connected.add(player.getUUID());tick(player);
            }
            previous.keySet().retainAll(connected);
        });
    }
    private static void tick(ServerPlayer player) {
        if(!player.isAlive() || player.isSpectator()) { previous.remove(player.getUUID());return; }
        List<ItemStack> items=new ArrayList<>();Set<UUID> now=new HashSet<>();
        Set<UUID> before=previous.get(player.getUUID());
        for(int i=0;i<player.getInventory().getContainerSize();i++) {
            var item=player.getInventory().getItem(i);
            if(!item.is(SpeakerRegistry.ITEM))continue;
            var found=SpeakerRegistry.ensureState(item);if(found.isEmpty())continue;
            var state=found.get();
            // Creative inventory cloning can duplicate the item UUID; each physical copy needs one.
            if(!now.add(state.id())) { state=state.copyForNewSpeaker();SpeakerData.write(item,state);now.add(state.id()); }
            if((before!=null && !before.contains(state.id())) || order(item)<=0) promote(player,item);
            items.add(item);
        }
        previous.put(player.getUUID(),now);
        var entries=items.stream().map(item->{var s=SpeakerData.read(item).orElseThrow();
            return new SpeakerInventoryPolicy.Entry(s.id(),s.main(),s.enabled(),order(item));}).toList();
        var selected=SpeakerInventoryPolicy.select(entries);
        for(var item:items) {
            var s=SpeakerData.read(item).orElseThrow();
            if(s.enabled() && !selected.contains(s.id())) { s=s.withEnabled(false);SpeakerData.write(item,s); }
            // Advance before broadcasting to match the block ticker checkpoint at END_SERVER_TICK.
            if(s.enabled() && !s.trackReference().isEmpty()) {
                s=s.withPlayback(s.trackReference(),Math.min(86_400_000L,s.positionMillis()+50));SpeakerData.write(item,s);
            }
            if((player.level().getGameTime()%5==0 || before==null || !before.contains(s.id())) && ServerPlayNetworking.canSend(player,SpeakerPlayback.Snapshot.TYPE)) {
                ServerPlayNetworking.send(player,new SpeakerPlayback.Snapshot(player.blockPosition(),s.id(),s.trackReference(),
                        s.positionMillis(),player.level().getGameTime()*50-s.positionMillis(),s.volume(),s.enabled(),true));
                SpeakerNetworking.refreshHand(player,s);
            }
        }
    }
    public static boolean finish(ServerPlayer player,SpeakerPlayback.End packet) {
        for(int i=0;i<player.getInventory().getContainerSize();i++) {
            var item=player.getInventory().getItem(i);if(!item.is(SpeakerRegistry.ITEM))continue;
            var state=SpeakerData.read(item);
            if(state.isEmpty() || !state.get().id().equals(packet.id()))continue;
            var s=state.get();
            if(s.enabled() && s.trackReference().equals(packet.track())
                    && Math.abs(player.level().getGameTime()*50-s.positionMillis()-packet.epoch())<=150) {
                SpeakerData.write(item,s.withEnabled(false).withPlayback(s.trackReference(),0));
                SpeakerNetworking.refreshHand(player,SpeakerData.read(item).orElseThrow());
            }
            return true;
        }
        return false;
    }
}
