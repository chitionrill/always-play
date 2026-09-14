package soke.musicdelay.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jspecify.annotations.Nullable;
import soke.musicdelay.client.playback.JukeboxDuckController;

// Ловим level event'ы, которыми сервер сообщает клиенту о старте/остановке звука пластинки
// в проигрывателе: SOUND_PLAY_JUKEBOX_SONG = 1010, SOUND_STOP_JUKEBOX_SONG = 1011.
// Это надёжный, real-time сигнал в отличие от блок-состояния HAS_RECORD (которое не отличает
// "трек играет" от "трек доиграл, но пластинку не вытащили").
@Mixin(ClientLevel.class)
public abstract class ClientLevelJukeboxMixin {

    @Inject(method = "levelEvent", at = @At("HEAD"))
    private void mdr$onLevelEvent(@Nullable Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (type == 1010) {
            JukeboxDuckController.onJukeboxSoundStart(pos);
        } else if (type == 1011) {
            JukeboxDuckController.onJukeboxSoundStop(pos);
        }
    }
}