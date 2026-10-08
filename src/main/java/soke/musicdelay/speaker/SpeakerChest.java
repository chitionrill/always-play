package soke.musicdelay.speaker;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Ticked only by ordinary chests in ticking chunks. Never forces chunks or loot tables open. */
public final class SpeakerChest {
    private static final String ORDER="always_play_chest_order", GROUP="always_play_chest_group";
    private static final Map<ChestBlockEntity,Set<UUID>> previous=new WeakHashMap<>();
    private record Slot(ChestBlockEntity chest,ItemStack item) { }
    private SpeakerChest() { }
    public static void register() { ServerLifecycleEvents.SERVER_STOPPED.register(server->previous.clear()); }

    private static ChestBlockEntity partner(ServerLevel level,ChestBlockEntity chest) {
        var state=chest.getBlockState();
        if(!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE)==ChestType.SINGLE)return chest;
        var pos=ChestBlock.getConnectedBlockPos(chest.getBlockPos(),state);
        if(!level.isLoaded(pos))return null;
        if(!(level.getBlockEntity(pos) instanceof ChestBlockEntity other))return null;
        var next=other.getBlockState();
        return ((ChestBlock)state.getBlock()).chestCanConnectTo(next) && next.getValue(ChestBlock.TYPE)!=ChestType.SINGLE
                && next.getValue(ChestBlock.TYPE)!=state.getValue(ChestBlock.TYPE)
                && next.getValue(ChestBlock.FACING)==state.getValue(ChestBlock.FACING)
                && ChestBlock.getConnectedBlockPos(pos,next).equals(chest.getBlockPos())?other:null;
    }
    private static long order(ItemStack item) { return item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getLong(ORDER).orElse(0L); }
    private static void order(ItemStack item,long value,String group) {
        var tag=item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        tag.putLong(ORDER,value);tag.putString(GROUP,group);item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
    }
    private static void collect(ChestBlockEntity chest,List<Slot> slots) {
        if(chest.getLootTable()!=null)return;
        for(int i=0;i<chest.getContainerSize();i++) {
            var item=chest.getItem(i);
            if(item.is(SpeakerRegistry.ITEM) && SpeakerRegistry.ensureState(item).isPresent())slots.add(new Slot(chest,item));
        }
    }
    public static void tick(ServerLevel level,ChestBlockEntity chest) {
        var other=partner(level,chest);
        // A double chest is processed by its canonical half, once per world tick.
        if(other==null || chest.getBlockPos().compareTo(other.getBlockPos())>0)return;
        if(!level.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.pack(other.getBlockPos())))return;
        var slots=new ArrayList<Slot>();collect(chest,slots);if(other!=chest)collect(other,slots);

        String group=level.dimension().toString()+":"+chest.getBlockPos().asLong();
        Set<UUID> before=previous.get(chest),now=new HashSet<>();
        long max=slots.stream().mapToLong(slot->order(slot.item)).max().orElse(0);
        if(max<0 || max>Long.MAX_VALUE-54) {
            var ranked=new ArrayList<>(slots);ranked.sort(Comparator.comparingLong(slot->order(slot.item)));
            max=0;for(var slot:ranked) { order(slot.item,++max,group);slot.chest.setChanged(); }
        }
        for(var slot:slots) {
            var s=SpeakerData.read(slot.item).orElseThrow();
            if(!now.add(s.id())) { s=s.copyForNewSpeaker();SpeakerData.write(slot.item,s);now.add(s.id());slot.chest.setChanged(); }
            var tag=slot.item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            if(order(slot.item)<=0 || !tag.getString(GROUP).orElse("").equals(group)
                    || (before!=null && !before.contains(s.id()))) {
                order(slot.item,++max,group);slot.chest.setChanged();
            }
        }
        // A cursor transfer within this chest is not a fresh insertion.
        for(var player:level.players()) {
            var held=player.containerMenu.getCarried();
            if(held.is(SpeakerRegistry.ITEM) && held.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY)
                    .copyTag().getString(GROUP).orElse("").equals(group))
                SpeakerData.read(held).ifPresent(data->now.add(data.id()));
        }
        previous.put(chest,now);
        var selected=SpeakerInventoryPolicy.select(slots.stream().map(slot->{var s=SpeakerData.read(slot.item).orElseThrow();
            return new SpeakerInventoryPolicy.Entry(s.id(),s.main(),s.enabled(),order(slot.item));}).toList());
        for(var slot:slots) {
            var s=SpeakerData.read(slot.item).orElseThrow();boolean changed=false;
            if(s.enabled() && !selected.contains(s.id())) { s=s.withEnabled(false);changed=true; }
            if(s.enabled() && !s.trackReference().isEmpty()) {
                s=s.withPlayback(s.trackReference(),Math.min(86_400_000L,s.positionMillis()+50));changed=true;
            }
            if(changed) { SpeakerData.write(slot.item,s);slot.chest.setChanged(); }
            if(level.getGameTime()%5==0 || before==null || !before.contains(s.id()))publish(level,chest.getBlockPos(),other.getBlockPos(),s);
        }
    }
    private static void publish(ServerLevel level,BlockPos pos,BlockPos other,SpeakerState s) {
        var packet=new SpeakerPlayback.Snapshot(pos,s.id(),s.trackReference(),s.positionMillis(),
                level.getGameTime()*50-s.positionMillis(),s.volume(),s.enabled(),false,-1,true,other);
        for(var player:level.players()) if(level.getServer().isSingleplayerOwner(player.nameAndId())
                && player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)<4096
                && ServerPlayNetworking.canSend(player,SpeakerPlayback.Snapshot.TYPE))ServerPlayNetworking.send(player,packet);
    }
    public static boolean finish(ServerPlayer player,SpeakerPlayback.End packet) {
        var level=player.level();
        if(!(level.getBlockEntity(packet.pos()) instanceof ChestBlockEntity chest) || !(chest.getBlockState().getBlock() instanceof ChestBlock))return false;
        var other=partner(level,chest);var slots=new ArrayList<Slot>();collect(chest,slots);
        if(other!=null && other!=chest)collect(other,slots);
        for(var slot:slots) {
            var s=SpeakerData.read(slot.item).orElseThrow();if(!s.id().equals(packet.id()))continue;
            if(s.enabled() && s.trackReference().equals(packet.track())
                    && Math.abs(level.getGameTime()*50-s.positionMillis()-packet.epoch())<=150) {
                s=s.withEnabled(false).withPlayback(s.trackReference(),0);SpeakerData.write(slot.item,s);slot.chest.setChanged();
                publish(level,chest.getBlockPos(),other==null?chest.getBlockPos():other.getBlockPos(),s);
            }
            return true;
        }
        return false;
    }
}
