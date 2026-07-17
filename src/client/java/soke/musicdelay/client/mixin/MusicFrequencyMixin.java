package soke.musicdelay.client.mixin;

import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.Music;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import soke.musicdelay.ModConfig;

@Mixin(MusicManager.MusicFrequency.class)
public class MusicFrequencyMixin {

    @Inject(method = "getNextSongDelay", at = @At("HEAD"), cancellable = true)
    private void shortenDelay(@Nullable Music music, RandomSource random, CallbackInfoReturnable<Integer> cir) {
        ModConfig config = ModConfig.get();
        int minTicks = config.minDelaySeconds * 20;
        int maxTicks = config.maxDelaySeconds * 20;
        int low = Math.min(minTicks, maxTicks);
        int high = Math.max(minTicks, maxTicks);
        if (low == high) high = low + 1;
        cir.setReturnValue(Mth.nextInt(random, low, high));
    }
}