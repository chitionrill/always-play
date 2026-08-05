package soke.musicdelay.client.playback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.sounds.SoundSource;
import soke.musicdelay.client.ModKeybindings;

// Обрабатывает удержание клавиш громкости (Up/Down) с ускорением при долгом зажатии.
public class VolumeKeyController {

    private static int volumeUpHeldTicks = 0;
    private static int volumeDownHeldTicks = 0;
    private static final int VOLUME_INITIAL_DELAY_TICKS = 8;
    private static final int VOLUME_REPEAT_INTERVAL_TICKS = 2;

    public static void tick(Minecraft client) {
        if (ModKeybindings.volumeUp.isDown()) {
            volumeUpHeldTicks++;
            if (shouldStep(volumeUpHeldTicks)) {
                adjustMusicVolume(client, 0.05);
            }
        } else {
            volumeUpHeldTicks = 0;
        }

        if (ModKeybindings.volumeDown.isDown()) {
            volumeDownHeldTicks++;
            if (shouldStep(volumeDownHeldTicks)) {
                adjustMusicVolume(client, -0.05);
            }
        } else {
            volumeDownHeldTicks = 0;
        }
    }

    private static boolean shouldStep(int heldTicks) {
        if (heldTicks == 1) return true;
        return heldTicks > VOLUME_INITIAL_DELAY_TICKS
                && (heldTicks - VOLUME_INITIAL_DELAY_TICKS) % VOLUME_REPEAT_INTERVAL_TICKS == 0;
    }

    private static void adjustMusicVolume(Minecraft client, double delta) {
        OptionInstance<Double> option = client.options.getSoundSourceOptionInstance(SoundSource.MUSIC);
        double current = option.get();
        double updated = Math.max(0.0, Math.min(1.0, current + delta));
        option.set(updated);
        client.options.save();
    }
}