package soke.musicdelay.speaker;

import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;

/** Read-only integrated-server verification at an already known location. No chunk loads. */
public final class SpeakerSourceCheck {
    public record Result(SpeakerPlayback.Snapshot snapshot,String reason) { }
    private SpeakerSourceCheck() { }
    private static List<ItemStack> items(Container container){
        var roots=new ArrayList<ItemStack>();
        for(int i=0;i<container.getContainerSize();i++)roots.add(container.getItem(i));
        return roots;
    }
    private static BlockEntity block(ServerLevel level,net.minecraft.core.BlockPos pos){
        var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        return chunk==null?null:chunk.getBlockEntity(pos);
    }
    public static Result inspect(ServerLevel level,ServerPlayer player,SpeakerPlayback.Snapshot old){
        if(level==null||player==null||player.level()!=level)return new Result(null,"world-or-player-gone");
        if(!level.getServer().isSingleplayer()||!level.getServer().isSingleplayerOwner(player.nameAndId()))
            return new Result(null,"not-local-owner");
        List<ItemStack> roots=new ArrayList<>();
        if(old.carried()){
            if(!player.isAlive()||player.isSpectator())return new Result(null,"carrier-unavailable");
            roots.addAll(items(player.getInventory()));roots.add(player.containerMenu.getCarried());
        }else if(old.itemEntityId()>=0){
            var entity=level.getEntity(old.itemEntityId());
            if(entity instanceof net.minecraft.world.entity.item.ItemEntity item&&!item.isRemoved())roots.add(item.getItem());
        }else if(old.ender()){
            if(!player.isAlive()||player.isSpectator())return new Result(null,"ender-owner-unavailable");
            var outlet=block(level,old.pos());
            if(outlet instanceof EnderChestBlockEntity&&outlet.getBlockState().is(Blocks.ENDER_CHEST))
                roots.addAll(items(player.getEnderChestInventory()));
        }else if(old.chest()){
            for(var pos:new LinkedHashSet<>(List.of(old.pos(),old.chestPartner()))){
                if(block(level,pos) instanceof ChestBlockEntity chest && chest.getLootTable()==null)roots.addAll(items(chest));
            }
        }else if(old.shell()){
            if(block(level,old.pos()) instanceof ShulkerBoxBlockEntity shell && shell.getLootTable()==null)roots.addAll(items(shell));
        }else if(block(level,old.pos()) instanceof SpeakerBlockEntity speaker){roots.add(speaker.itemCopy());}
        // The existing storage view reads direct items or one supported shulker layer; never commits.
        var view=new SpeakerStorage.View(roots);
        for(var item:view.items){
            var state=SpeakerData.read(item).orElse(null);
            if(state==null||!state.id().equals(old.id()))continue;
            var pos=old.carried()?player.blockPosition():old.pos();
            return new Result(new SpeakerPlayback.Snapshot(pos,state.id(),state.trackReference(),state.positionMillis(),
                    level.getGameTime()*50-state.positionMillis(),state.volume(),state.enabled(),old.carried(),old.itemEntityId(),
                    old.chest(),old.carried()?pos:old.chestPartner(),old.shell(),old.ender()),state.enabled()?"confirmed":"confirmed-disabled");
        }
        return new Result(null,"identity-not-present");
    }
}
