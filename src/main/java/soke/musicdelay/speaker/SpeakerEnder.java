package soke.musicdelay.speaker;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;

/** The player's private storage is the source; the nearest loaded chest is only its audible outlet. */
public final class SpeakerEnder {
    private static final Map<UUID,Set<UUID>> previous=new HashMap<>();
    private SpeakerEnder() { }
    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server->previous.clear());
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(!server.isSingleplayer())return;
            Set<UUID> connected=new HashSet<>();
            for(var player:server.getPlayerList().getPlayers())if(server.isSingleplayerOwner(player.nameAndId())) {
                connected.add(player.getUUID());tick(player);
            }
            previous.keySet().retainAll(connected);
        });
    }
    private static long order(ItemStack item) {return item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getLong("always_play_ender_order").orElse(0L);}
    private static void order(ItemStack item,long rank) {
        var tag=item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();tag.putLong("always_play_ender_order",rank);
        item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
    }
    private static List<ItemStack> items(ServerPlayer player) {
        List<ItemStack> items=new ArrayList<>();var storage=player.getEnderChestInventory();
        for(int i=0;i<storage.getContainerSize();i++) {
            var item=storage.getItem(i);if(item.is(SpeakerRegistry.ITEM) && SpeakerRegistry.ensureState(item).isPresent())items.add(item);
        }
        return items;
    }
    private static BlockPos nearest(ServerPlayer player) {
        var centre=player.blockPosition();BlockPos best=null;double distance=32*32;
        var chunks=player.level().getChunkSource();
        // Inspect only existing block entities in 25 already-loaded chunks, never a 65-cubed block scan.
        for(int x=(centre.getX()>>4)-2;x<=(centre.getX()>>4)+2;x++)
            for(int z=(centre.getZ()>>4)-2;z<=(centre.getZ()>>4)+2;z++) {
                var chunk=chunks.getChunkNow(x,z);if(chunk==null)continue;
                for(var entity:chunk.getBlockEntities().values()) {
                    if(!(entity instanceof net.minecraft.world.level.block.entity.EnderChestBlockEntity) || entity.isRemoved())continue;
                    var pos=entity.getBlockPos();
                    double d=player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos));
                    if(d<distance) { best=pos.immutable();distance=d; }
                }
            }
        return best;
    }
    private static void tick(ServerPlayer player) {
        var items=items(player);var before=previous.get(player.getUUID());var now=new HashSet<UUID>();
        long max=items.stream().mapToLong(SpeakerEnder::order).max().orElse(0);
        if(max<0 || max>Long.MAX_VALUE-27) {
            var sorted=new ArrayList<>(items);sorted.sort(Comparator.comparingLong(SpeakerEnder::order));max=0;
            for(var item:sorted)order(item,++max);
        }
        for(var item:items) {
            var s=SpeakerData.read(item).orElseThrow();
            if(!now.add(s.id())){s=s.copyForNewSpeaker();SpeakerData.write(item,s);now.add(s.id());}
            if(order(item)<=0 || before!=null && !before.contains(s.id()))order(item,++max);
        }
        if(before!=null)SpeakerData.read(player.containerMenu.getCarried()).filter(s->before.contains(s.id())).ifPresent(s->now.add(s.id()));
        previous.put(player.getUUID(),now);
        var selected=SpeakerInventoryPolicy.select(items.stream().map(item->{var s=SpeakerData.read(item).orElseThrow();
            return new SpeakerInventoryPolicy.Entry(s.id(),s.main(),s.enabled(),order(item));}).toList());
        long time=player.level().getGameTime();boolean publish=time%5==0 || before==null || !before.equals(now);
        BlockPos pos=null;
        if(publish && !items.isEmpty() && player.isAlive() && !player.isSpectator())pos=nearest(player);
        for(var item:items) {
            var s=SpeakerData.read(item).orElseThrow();
            if(s.enabled() && !selected.contains(s.id()))s=s.withEnabled(false);
            if(s.enabled() && !s.trackReference().isEmpty())s=s.withPlayback(s.trackReference(),Math.min(86_400_000L,s.positionMillis()+50));
            SpeakerData.write(item,s);
            if(pos!=null && ServerPlayNetworking.canSend(player,SpeakerPlayback.Snapshot.TYPE))
                ServerPlayNetworking.send(player,new SpeakerPlayback.Snapshot(pos,s.id(),s.trackReference(),s.positionMillis(),
                        time*50-s.positionMillis(),s.volume(),s.enabled(),false,-1,false,pos,false,true));
        }
        if(!items.isEmpty())player.getEnderChestInventory().setChanged();
    }
    public static boolean finish(ServerPlayer player,SpeakerPlayback.End packet) {
        if(!player.level().getBlockState(packet.pos()).is(Blocks.ENDER_CHEST))return false;
        for(var item:items(player)) {
            var s=SpeakerData.read(item).orElseThrow();if(!s.id().equals(packet.id()))continue;
            if(s.enabled() && s.trackReference().equals(packet.track()) && Math.abs(player.level().getGameTime()*50-s.positionMillis()-packet.epoch())<=150) {
                SpeakerData.write(item,s.withEnabled(false).withPlayback(s.trackReference(),0));player.getEnderChestInventory().setChanged();
            }
            return true;
        }
        return false;
    }
}
