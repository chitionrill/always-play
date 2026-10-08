package soke.musicdelay.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import soke.musicdelay.speaker.SpeakerChest;

/** Keep the existing ticker (including client lid animation) intact. */
@Mixin(ChestBlock.class)
public abstract class ChestSpeakerMixin {
    @Inject(method="getTicker",at=@At("RETURN"),cancellable=true)
    private <T extends BlockEntity> void alwaysPlay$chestTicker(Level level, BlockState state,
                                                                BlockEntityType<T> type, CallbackInfoReturnable<BlockEntityTicker<T>> result) {
        if(!(level instanceof net.minecraft.server.level.ServerLevel server)
                || !server.getServer().isSingleplayer() || !(state.getBlock() instanceof ChestBlock)
                || type!=BlockEntityTypes.CHEST && type!=BlockEntityTypes.TRAPPED_CHEST) return;
        BlockEntityTicker<T> existing=result.getReturnValue();
        result.setReturnValue((world,pos,block,entity)->{
            if(existing!=null) existing.tick(world,pos,block,entity);
            if(entity instanceof ChestBlockEntity chest) SpeakerChest.tick(server,chest);
        });
    }
}
