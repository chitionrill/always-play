package soke.musicdelay.client;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

public class FixedSoundInstance extends AbstractSoundInstance {

    private final Sound fixedSound;

    public FixedSoundInstance(Sound fixedSound, Identifier categoryLocation, SoundSource source, RandomSource random) {
        super(categoryLocation, source, random);
        this.fixedSound = fixedSound;
        this.volume = 1.0F;
        this.pitch = 1.0F;
        this.looping = false;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = true;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }

    @Override
    public @Nullable WeighedSoundEvents resolve(SoundManager soundManager) {
        this.sound = fixedSound;
        WeighedSoundEvents events = new WeighedSoundEvents(this.identifier, null);
        events.addSound(fixedSound);
        return events;
    }
}