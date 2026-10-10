package soke.musicdelay.client.speaker;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import soke.musicdelay.client.speaker.audio.*;

/** All fluid queries stay on the client thread and never load chunks. */
public final class SpeakerWaterWorld {
    private SpeakerWaterWorld() { }
    public static SpeakerWater.Profile measure(Minecraft mc,SpeakerPropagation.Point source,SpeakerPropagation.Point listener,
                                               List<SpeakerPropagation.Point> knownRoute,boolean placed){
        var cache=new HashMap<BlockPos,Double>();
        SpeakerWater.Field field=(x,y,z)->{
            var pos=BlockPos.containing(x,y,z);
            double top=cache.computeIfAbsent(pos,p->{
                if(!mc.level.isLoaded(p))return Double.NEGATIVE_INFINITY;
                var fluid=mc.level.getFluidState(p);
                return fluid.is(FluidTags.WATER)?p.getY()+(double)fluid.getHeight(mc.level,p):Double.NEGATIVE_INFINITY;
            });
            return y<top;
        };
        boolean sourceWet=field.water(source.x(),source.y(),source.z());
        if(placed && !sourceWet){
            int count=0;
            double[][] sides={{.55,0,0},{-.55,0,0},{0,.55,0},{0,-.55,0},{0,0,.55},{0,0,-.55}};
            for(var side:sides)if(field.water(source.x()+side[0],source.y()+side[1],source.z()+side[2]))count++;
            sourceWet=count>=4;
        }
        boolean listenerWet=field.water(listener.x(),listener.y(),listener.z());
        var route=new ArrayList<SpeakerPropagation.Point>();
        if(knownRoute.size()>=2 && knownRoute.size()<=32){route.addAll(knownRoute);route.set(0,source);route.set(route.size()-1,listener);}
        else {route.add(source);route.add(listener);}
        var sourceCell=BlockPos.containing(source.x(),source.y(),source.z());
        boolean displacedWater=placed && sourceWet;
        SpeakerWater.Field pathField=(x,y,z)->(displacedWater&&BlockPos.containing(x,y,z).equals(sourceCell))||field.water(x,y,z);
        return SpeakerWater.profile(sourceWet,listenerWet,SpeakerWater.measure(pathField,route,sourceWet,listenerWet));
    }
}
