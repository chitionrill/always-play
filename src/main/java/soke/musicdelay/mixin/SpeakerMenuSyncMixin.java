package soke.musicdelay.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import soke.musicdelay.speaker.SpeakerInventory;
import soke.musicdelay.speaker.SpeakerRegistry;

/** Playback checkpoints change stack components while the client predicts inventory clicks. */
@Mixin(AbstractContainerMenu.class)
public abstract class SpeakerMenuSyncMixin {
    @Inject(method="clicked",at=@At("HEAD"))
    private void alwaysPlay$syncSpeakerMove(int slot,int button,ContainerInput input,Player player,CallbackInfo ci) {
        if(!(player instanceof ServerPlayer serverPlayer))return;
        var menu=(AbstractContainerMenu)(Object)this;
        if(soke.musicdelay.speaker.SpeakerStorage.relevant(menu.getCarried())
                || slot>=0 && slot<menu.slots.size() && soke.musicdelay.speaker.SpeakerStorage.relevant(menu.slots.get(slot).getItem()))
            SpeakerInventory.requestMenuSync(serverPlayer);
    }
}
