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
    private static final Set<UUID> menuSyncPending=new HashSet<>();
    public static void requestMenuSync(ServerPlayer player) {
        if(player.level().getServer().isSingleplayer())menuSyncPending.add(player.getUUID());
    }
    private SpeakerInventory() { }
    static long order(ItemStack item) {
        return item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getLong(ORDER).orElse(0L);
    }
    static void order(ItemStack item,long value) {
        var tag=item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        tag.putLong(ORDER,value);item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
    }
    private static List<ItemStack> items(ServerPlayer player) {
        List<ItemStack> result=new ArrayList<>();
        for(int i=0;i<player.getInventory().getContainerSize();i++)result.add(player.getInventory().getItem(i));
        var cursor=player.containerMenu.getCarried();
        if(!cursor.isEmpty())result.add(cursor);
        return result;
    }
    public static void promote(ServerPlayer player,ItemStack item) {
        var view=new SpeakerStorage.View(items(player));promote(view.items,item);view.commit();
    }
    static void promote(List<ItemStack> all,ItemStack item) {
        long max=all.stream().mapToLong(SpeakerInventory::order).max().orElse(0);
        if(max==Long.MAX_VALUE) {
            var ranked=new ArrayList<>(all);ranked.sort(Comparator.comparingLong(SpeakerInventory::order));
            max=0;for(var stack:ranked)order(stack,++max);
        }
        order(item,max+1);
    }
    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{ previous.clear();menuSyncPending.clear(); });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(!server.isSingleplayer())return;
            Set<UUID> connected=new HashSet<>();
            for(var player:server.getPlayerList().getPlayers()) if(server.isSingleplayerOwner(player.nameAndId())) {
                connected.add(player.getUUID());tick(player);
                // Flush after vanilla processes predicted slot/cursor hashes, not inside clicked().
                if(menuSyncPending.remove(player.getUUID()))player.containerMenu.broadcastFullState();
            }
            previous.keySet().retainAll(connected);menuSyncPending.retainAll(connected);
        });
    }
    private static void tick(ServerPlayer player) {
        if(!player.isAlive() || player.isSpectator()) { previous.remove(player.getUUID());return; }
        var view=new SpeakerStorage.View(items(player));
        List<ItemStack> items=new ArrayList<>();Set<UUID> now=new HashSet<>();
        Set<UUID> before=previous.get(player.getUUID());
        for(var item:view.items) {

            if(!item.is(SpeakerRegistry.ITEM))continue;
            var found=SpeakerRegistry.ensureState(item);if(found.isEmpty())continue;
            var state=found.get();
            // Creative inventory cloning can duplicate the item UUID; each physical copy needs one.
            if(!now.add(state.id())) { state=state.copyForNewSpeaker();SpeakerData.write(item,state);now.add(state.id()); }
            if((before!=null && !before.contains(state.id())) || order(item)<=0) promote(view.items,item);
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
                        s.positionMillis(),player.level().getGameTime()*50-s.positionMillis(),s.volume(),s.enabled(),true,-1,false,player.blockPosition(),view.nested.contains(item)));
                SpeakerNetworking.refreshHand(player,s);
            }
        }
        view.commit();
    }
    /** Called for a newly thrown item before its first audio snapshot, not for old saved drops. */
    public static void prepareDrop(ServerPlayer player,net.minecraft.world.entity.item.ItemEntity dropped) {
        var roots=items(player);roots.add(dropped.getItem());var view=new SpeakerStorage.View(roots);
        var candidates=view.items.stream().filter(item->SpeakerData.read(item).isPresent()).toList();
        var selected=SpeakerInventoryPolicy.select(candidates.stream().map(item->{var state=SpeakerData.read(item).orElseThrow();
            return new SpeakerInventoryPolicy.Entry(state.id(),state.main(),state.enabled(),order(item));}).toList());
        for(var item:candidates) {
            var state=SpeakerData.read(item).orElseThrow();
            if(state.enabled() && !selected.contains(state.id()))SpeakerData.write(item,state.withEnabled(false));
        }
        view.commit();dropped.setItem(dropped.getItem().copy());
    }

    public static boolean finish(ServerPlayer player,SpeakerPlayback.End packet) {
        var view=new SpeakerStorage.View(items(player));
        for(var item:view.items) {
            if(!item.is(SpeakerRegistry.ITEM))continue;
            var state=SpeakerData.read(item);
            if(state.isEmpty() || !state.get().id().equals(packet.id()))continue;
            var s=state.get();
            if(s.enabled() && s.trackReference().equals(packet.track())
                    && Math.abs(player.level().getGameTime()*50-s.positionMillis()-packet.epoch())<=150) {
                SpeakerData.write(item,s.withEnabled(false).withPlayback(s.trackReference(),0));
                SpeakerNetworking.refreshHand(player,SpeakerData.read(item).orElseThrow());
            }
            view.commit();return true;
        }
        return false;
    }
}
