package soke.musicdelay.client.speaker;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import soke.musicdelay.client.speaker.audio.SpeakerPropagation;
import soke.musicdelay.client.speaker.audio.SpeakerEnergy;

/** Client-thread world access only. The worker receives immutable path parameters and reflection taps. */
public final class SpeakerAcoustics {
    private record Material(List<AABB> shapes,double loss,double reflection,SpeakerEnergy.Voxel voxel) { }
    private SpeakerAcoustics() { }
    public static SpeakerPropagation.Job begin(Minecraft mc,double x,double y,double z,BlockPos source,BlockPos partner,float volume) {
        return new SpeakerPropagation(field(mc,source,partner)).begin(new SpeakerPropagation.Point(x,y,z),new SpeakerPropagation.Point(mc.player.getX(),mc.player.getEyeY(),mc.player.getZ()),24*volume);
    }
    public static SpeakerEnergy.Transport energy(Minecraft mc,SpeakerPropagation.Point sourcePoint,SpeakerPropagation.Point listener,BlockPos source,BlockPos partner){
        return new SpeakerEnergy.Transport(field(mc,source,partner),sourcePoint,listener);
    }
    private static Material material(List<AABB> shapes,double loss,double reflection){
        var boxes=shapes.stream().map(b->new SpeakerEnergy.Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ)).toList();
        return new Material(shapes,loss,reflection,new SpeakerEnergy.Voxel(boxes,new SpeakerEnergy.Material(loss,reflection)));
    }
    private static SpeakerPropagation.Field field(Minecraft mc,BlockPos source,BlockPos partner){
        var cache=new HashMap<BlockPos,Material>();
        return new SpeakerPropagation.Field() {
            private Material material(BlockPos pos) {
                return cache.computeIfAbsent(pos,p->{
                    if(!mc.level.isLoaded(p))return SpeakerAcoustics.material(List.of(new AABB(0,0,0,1,1,1)),8,0);
                    var state=mc.level.getBlockState(p);
                    double loss=1.35,reflection=.70;
                    if(state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)){loss=2.4;reflection=.06;}
                    else if(state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS) || state.is(BlockTags.WOODEN_STAIRS) || state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.FENCE_GATES)){loss=.85;reflection=.38;}
                    else if(state.is(BlockTags.LEAVES)){loss=.3;reflection=.08;}
                    else {
                        String name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                        if(name.contains("glass")){loss=.65;reflection=.75;}
                        else if(Set.of("sand","red_sand","gravel","dirt","coarse_dirt","rooted_dirt","grass_block","podzol","mycelium","mud","packed_mud","snow","snow_block").contains(name)){loss=1.1;reflection=.18;}
                        else if(!name.endsWith("_ore") && (name.contains("copper") || name.contains("iron") || name.contains("gold") || name.contains("netherite"))){loss=1.8;reflection=.85;}
                    }
                    // The actual thin door shape supplies its thickness. Closed doors must still isolate.
                    if(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                            || state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock){
                        loss=6;
                        String name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                        reflection=name.contains("copper") || name.contains("iron")?.85:.38;
                    }
                    return SpeakerAcoustics.material(state.getCollisionShape(mc.level,p).toAabbs(),loss,reflection);
                });
            }
            @Override public SpeakerEnergy.Voxel voxel(int x,int y,int z){
                var pos=new BlockPos(x,y,z);
                return pos.equals(source)||pos.equals(partner)?SpeakerEnergy.Voxel.AIR:material(pos).voxel;
            }
            @Override public double reflection(double x,double y,double z){return material(BlockPos.containing(x,y,z)).reflection;}
            @Override public double loss(double px,double py,double pz){
                var pos=BlockPos.containing(px,py,pz);
                // The emitter's own casing already has its own filter; never count it as a wall.
                if(pos.equals(source) || pos.equals(partner))return 0;
                var material=material(pos);
                double lx=px-pos.getX(),ly=py-pos.getY(),lz=pz-pos.getZ();
                for(var shape:material.shapes)if(shape.contains(lx,ly,lz))return material.loss;
                return 0;
            }
        };
    }
}
