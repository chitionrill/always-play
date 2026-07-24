package soke.musicdelay.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import soke.musicdelay.ModConfig;
import soke.musicdelay.client.FixedSoundInstance;
import soke.musicdelay.client.IMusicManagerMixin;
import soke.musicdelay.client.MusicTracker;
import soke.musicdelay.client.UnifiedTrack;

@Mixin(MusicManager.class)
public abstract class MusicManagerMixin implements IMusicManagerMixin {

    @Shadow
    private @Nullable SoundInstance currentMusic;

    @Shadow
    private int nextSongDelay;

    @Shadow
    private boolean toastShown;

    @Shadow
    public abstract void stopPlaying();

    @Shadow
    public abstract void startPlaying(Music music);

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void mdr$blockVanillaAutoplay(CallbackInfo ci) {
        if (soke.musicdelay.client.MusicDelayReducerClient.startupBlocking || !"VANILLA".equals(ModConfig.get().playbackMode)) {
            ci.cancel();
        }
    }

    @Inject(method = "startPlaying", at = @At("TAIL"))
    private void onTrackStarted(Music music, CallbackInfo ci) {
        if (!MusicTracker.get().isNavigating() && this.currentMusic != null) {
            Sound sound = this.currentMusic.getSound();
            if (sound != null) {
                MusicTracker.get().onTrackStarted(UnifiedTrack.ofVanilla(sound));
            }
        }
    }

    @Override
    @Unique
    public void mdr$stopAndBlock() {
        stopPlaying();
        this.nextSongDelay = Integer.MAX_VALUE;
        this.toastShown = false;
    }

    @Override
    @Unique
    public void mdr$playFixed(Sound sound) {
        // Примечание: setNavigating(true/false) здесь не нужен — этот путь не вызывает
        // vanilla startPlaying(), поэтому наш перехват onTrackStarted (который проверяет
        // isNavigating()) в принципе не срабатывает для этого метода
        SoundInstance instance = new FixedSoundInstance(
                sound,
                Identifier.fromNamespaceAndPath("music-delay-reducer", "fixed_music"),
                SoundSource.MUSIC,
                SoundInstance.createUnseededRandom()
        );
        Minecraft.getInstance().getSoundManager().play(instance);
        this.currentMusic = instance;
        this.nextSongDelay = Integer.MAX_VALUE;
        this.toastShown = true;
    }

    @Override
    @Unique
    public void mdr$playVanillaRandom(Music music) {
        startPlaying(music);
        this.nextSongDelay = Integer.MAX_VALUE;
    }

    @Override
    @Unique
    public void mdr$unblock(int delayTicks) {
        this.nextSongDelay = Math.max(delayTicks, 0);
    }

    @Override
    @Unique
    public boolean mdr$isVanillaActive() {
        return this.currentMusic != null && Minecraft.getInstance().getSoundManager().isActive(this.currentMusic);
    }

    @Override
    @Unique
    public void mdr$setGain(float gain) {
        Minecraft.getInstance().getSoundManager().updateCategoryVolume(SoundSource.MUSIC, gain);
    }

    @Override
    @Unique
    public Sound mdr$getCurrentSound() {
        return this.currentMusic != null ? this.currentMusic.getSound() : null;
    }
}