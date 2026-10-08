package soke.musicdelay.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import soke.musicdelay.speaker.SpeakerData;
import soke.musicdelay.speaker.SpeakerRegistry;

/** Creative placement makes a new physical container; Survival keeps playback identities. */
@Mixin(BlockItem.class)
public abstract class ShulkerPlacementMixin {
    @Inject(method="place",at=@At("RETURN"))
    private void alwaysPlay$newBoxIdentity(BlockPlaceContext context,CallbackInfoReturnable<InteractionResult> result) {
        if(!result.getReturnValue().consumesAction() || !(context.getLevel() instanceof ServerLevel)
                || context.getPlayer()==null || !context.getPlayer().getAbilities().instabuild
                || !(((BlockItem)(Object)this).getBlock() instanceof ShulkerBoxBlock))return;
        if(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof ShulkerBoxBlockEntity box && box.getLootTable()==null) {
            for(int i=0;i<box.getContainerSize();i++) {
                var item=box.getItem(i);
                if(item.is(SpeakerRegistry.ITEM))SpeakerRegistry.ensureState(item).ifPresent(s->SpeakerData.write(item,s.copyForNewSpeaker()));
            }
            box.setChanged();
        }
    }
}
