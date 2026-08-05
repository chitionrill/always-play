package soke.musicdelay.client.playback;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import soke.musicdelay.ModConfig;
import soke.musicdelay.client.IMusicManagerMixin;
import soke.musicdelay.client.WavPlayer;

// Отвечает за последовательность запуска: тишина/countdown перед первым звуком
// и плавный fade-in ванильной музыки после старта.
// Вынесено из MusicDelayReducerClient как первый шаг декомпозиции God-class.
public class StartupSequencer {

    public static volatile boolean startupBlocking = true;
    private static boolean startupInitialized = false;
    private static int startupCountdown = 0;
    private static boolean startupHandled = false;
    private static boolean pendingStartupFade = false;

    private static boolean vanillaStartupFadePending = false;
    private static long vanillaStartupFadeStartNanos = 0;

    // Вызывать первым в тике. Возвращает true, если тик должен остановиться здесь
    // (мы всё ещё внутри стартовой паузы) — тогда вызывающий код делает return.
    public static boolean tick(IMusicManagerMixin mixin, ModConfig config, boolean playlistMode, String mode) {
        if (startupHandled) return false;

        if (!startupInitialized) {
            startupInitialized = true;
            startupCountdown = config.startupFadeEnabled
                    ? Math.max(1, config.startupDelaySeconds) * 20
                    : 0;
            pendingStartupFade = config.startupFadeEnabled;
        }

        if (startupCountdown > 0) {
            startupCountdown--;
            mixin.mdr$stopAndBlock();
            WavPlayer.stop();
            return true;
        }

        startupHandled = true;
        startupBlocking = false;

        if (!playlistMode && "VANILLA".equals(mode)) {
            mixin.mdr$unblock(1);
        }
        vanillaStartupFadePending = pendingStartupFade;
        return false;
    }

    // Вызывать сразу после tick(), если тот вернул false.
    public static void tickVanillaFade(Minecraft client, IMusicManagerMixin mixin, ModConfig config) {
        if (!vanillaStartupFadePending || !mixin.mdr$isVanillaActive()) return;

        if (vanillaStartupFadeStartNanos == 0) {
            vanillaStartupFadeStartNanos = System.nanoTime();
        }
        double durationNanos = Math.max(0.1, config.crossfadeDurationSeconds) * 1_000_000_000L;
        double progress = Math.min(1.0, (System.nanoTime() - vanillaStartupFadeStartNanos) / durationNanos);
        float sliderVolume = client.options.getSoundSourceVolume(SoundSource.MUSIC);
        mixin.mdr$setGain((float) (sliderVolume * progress));
        if (progress >= 1.0) {
            vanillaStartupFadePending = false;
            vanillaStartupFadeStartNanos = 0;
        }
    }

    public static boolean consumeStartupFadeFlag() {
        if (pendingStartupFade) {
            pendingStartupFade = false;
            return true;
        }
        return false;
    }

    public static boolean isBlocking() {
        return startupBlocking;
    }

    // Вызывать из restartForWorldJoin() при заходе в новый мир.
    public static void reset() {
        startupInitialized = false;
        startupHandled = false;
        startupBlocking = true;
        pendingStartupFade = false;
        vanillaStartupFadePending = false;
        vanillaStartupFadeStartNanos = 0;
    }
}