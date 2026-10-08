package soke.musicdelay.speaker;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

public final class SpeakerShulker {
    private static final Map<ShulkerBoxBlockEntity,Set<UUID>> previous=new WeakHashMap<>();
    private SpeakerShulker() { }
    private static long order(ItemStack item) {
        return item.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY)
                .copyTag().getLong("always_play_shulker_order").orElse(0L);
    }
    private static void order(ItemStack item,long rank) {
        var tag=item.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        tag.putLong("always_play_shulker_order",rank);item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));
    }
    public static void register() { ServerLifecycleEvents.SERVER_STOPPED.register(server->previous.clear()); }
    private static List<ItemStack> contents(ShulkerBoxBlockEntity box) {
        var items=new ArrayList<ItemStack>();if(box.getLootTable()!=null)return items;
        for(int i=0;i<box.getContainerSize();i++) {
            var item=box.getItem(i);if(item.is(SpeakerRegistry.ITEM) && SpeakerRegistry.ensureState(item).isPresent())items.add(item);
        }
        return items;
    }
    public static void tick(ServerLevel level,ShulkerBoxBlockEntity box) {
        var items=contents(box);var before=previous.get(box);var now=new HashSet<UUID>();
        long rank=items.stream().mapToLong(SpeakerShulker::order).max().orElse(0);
        if(rank>Long.MAX_VALUE-27 || rank<0) {
            var sorted=new ArrayList<>(items);sorted.sort(Comparator.comparingLong(SpeakerShulker::order));
            rank=0;for(var item:sorted)order(item,++rank);
        }
        for(var item:items) {
            var s=SpeakerData.read(item).orElseThrow();
            if(!now.add(s.id())) { s=s.copyForNewSpeaker();SpeakerData.write(item,s);now.add(s.id()); }
            if(order(item)<=0 || before!=null && !before.contains(s.id()))order(item,++rank);
        }
        // Keep a temporarily cursor-held member in this group's insertion history.
        if(before!=null)for(var player:level.players())SpeakerData.read(player.containerMenu.getCarried())
                .filter(s->before.contains(s.id())).ifPresent(s->now.add(s.id()));
        previous.put(box,now);
        var selected=SpeakerInventoryPolicy.select(items.stream().map(item->{var s=SpeakerData.read(item).orElseThrow();
            return new SpeakerInventoryPolicy.Entry(s.id(),s.main(),s.enabled(),order(item));}).toList());
        for(var item:items) {
            var s=SpeakerData.read(item).orElseThrow();
            if(s.enabled() && !selected.contains(s.id()))s=s.withEnabled(false);
            if(s.enabled() && !s.trackReference().isEmpty())s=s.withPlayback(s.trackReference(),Math.min(86_400_000L,s.positionMillis()+50));
            SpeakerData.write(item,s);
            if(level.getGameTime()%5==0 || before==null || !before.contains(s.id()))publish(level,box,s);
        }
        if(!items.isEmpty())box.setChanged();
    }
    private static void publish(ServerLevel level,ShulkerBoxBlockEntity box,SpeakerState s) {
        var pos=box.getBlockPos();var packet=new SpeakerPlayback.Snapshot(pos,s.id(),s.trackReference(),s.positionMillis(),
                level.getGameTime()*50-s.positionMillis(),s.volume(),s.enabled(),false,-1,false,pos,true);
        for(var player:level.players())if(level.getServer().isSingleplayerOwner(player.nameAndId())
                && player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)<4096
                && ServerPlayNetworking.canSend(player,SpeakerPlayback.Snapshot.TYPE))ServerPlayNetworking.send(player,packet);
    }
    public static boolean finish(ServerPlayer player,SpeakerPlayback.End packet) {
        if(!(player.level().getBlockEntity(packet.pos()) instanceof ShulkerBoxBlockEntity box))return false;
        for(var item:contents(box)) {
            var s=SpeakerData.read(item).orElseThrow();if(!s.id().equals(packet.id()))continue;
            if(s.enabled() && s.trackReference().equals(packet.track())
                    && Math.abs(player.level().getGameTime()*50-s.positionMillis()-packet.epoch())<=150) {
                s=s.withEnabled(false).withPlayback(s.trackReference(),0);SpeakerData.write(item,s);box.setChanged();publish(player.level(),box,s);
            }
            return true;
        }
        return false;
    }
}
