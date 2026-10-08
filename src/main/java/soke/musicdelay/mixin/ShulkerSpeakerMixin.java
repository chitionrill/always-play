package soke.musicdelay.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import soke.musicdelay.speaker.SpeakerShulker;

@Mixin(ShulkerBoxBlockEntity.class)
public abstract class ShulkerSpeakerMixin {
    @Inject(method="tick",at=@At("TAIL"))
    private static void alwaysPlay$tick(Level level,BlockPos pos,BlockState state,ShulkerBoxBlockEntity box,CallbackInfo ci) {
        if(level instanceof ServerLevel server && server.getServer().isSingleplayer())SpeakerShulker.tick(server,box);
    }
}
