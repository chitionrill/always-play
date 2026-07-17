package soke.musicdelay.client;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.sounds.Music;

public interface IMusicManagerMixin {
    void mdr$stopAndBlock();
    void mdr$playFixed(Sound sound);
    void mdr$playVanillaRandom(Music music);
    void mdr$unblock(int delayTicks);
    boolean mdr$isVanillaActive();
    void mdr$setGain(float gain);
}