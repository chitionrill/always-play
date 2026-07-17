package soke.musicdelay.client.mixin;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.client.sounds.WeighedSoundEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;

@Mixin(WeighedSoundEvents.class)
public abstract class WeighedSoundEventsMixin implements soke.musicdelay.client.IWeighedSoundEventsMixin {

    @Shadow
    private List<Weighted<Sound>> list;

    @Override
    @Unique
    public List<Sound> mdr$getAllSounds() {
        List<Sound> result = new ArrayList<>();
        for (Weighted<Sound> weighted : list) {
            if (weighted instanceof Sound sound) {
                result.add(sound);
            }
        }
        return result;
    }
}