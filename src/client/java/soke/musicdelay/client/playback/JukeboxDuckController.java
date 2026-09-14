package soke.musicdelay.client.playback;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import soke.musicdelay.ModConfig;
import soke.musicdelay.client.IMusicManagerMixin;
import soke.musicdelay.client.WavPlayer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Плавно приглушает музыку MDR, пока рядом с игроком реально звучит пластинка в проигрывателе, и
// плавно возвращает громкость обратно, когда трек закончился/пластинку вытащили ИЛИ игрок отошёл
// за пределы детект-радиуса.
//
// Детект: ClientLevelJukeboxMixin ловит level event'ы SOUND_PLAY_JUKEBOX_SONG (1010) и
// SOUND_STOP_JUKEBOX_SONG (1011), которыми сервер сообщает клиенту именно о факте "звук начал/
// перестал звучать" — в отличие от блок-состояния HAS_RECORD, это различает "трек играет" и
// "трек доиграл, но пластинку не вытащили".
//
// Для кастомных треков (WavPlayer): как только fade-out доходит до 0 — трек реально СТАВИТСЯ НА
// ПАУЗУ (WavPlayer.pause()), а не просто продолжает беззвучно крутиться. Возобновление
// (WavPlayer.resume()) происходит в момент, когда начинается fade-in обратно.
//
// Для ванильной музыки (Minecraft MusicManager) реального "паузного" API в этом моде нет —
// даже ручная пауза (клавиша Pause/Resume) для неё работает только через приглушение громкости
// (mdr$setGain), поэтому здесь для ванильного режима оставлено так же.
public class JukeboxDuckController {

    private static final int SCAN_INTERVAL_TICKS = 5;

    // Позиции проигрывателей, для которых мы сейчас точно знаем (по level event'ам), что трек
    // реально звучит. thread-safe на случай, если mixin сработает не в основном тик-потоке.
    private static final Set<BlockPos> playingJukeboxes = ConcurrentHashMap.newKeySet();

    private static float duckFactor = 1.0f; // 1.0 = музыка MDR полностью слышна, 0.0 = приглушена
    private static int scanCountdown = 0;
    private static boolean jukeboxNearby = false;
    private static boolean enginePausedByDuck = false;

    // Вызывается из ClientLevelJukeboxMixin при событии SOUND_PLAY_JUKEBOX_SONG (1010).
    public static void onJukeboxSoundStart(BlockPos pos) {
        playingJukeboxes.add(pos.immutable());
    }

    // Вызывается из ClientLevelJukeboxMixin при событии SOUND_STOP_JUKEBOX_SONG (1011).
    public static void onJukeboxSoundStop(BlockPos pos) {
        playingJukeboxes.remove(pos.immutable());
    }

    public static void tick(Minecraft client, IMusicManagerMixin mixin) {
        ModConfig config = ModConfig.get();

        if (!config.jukeboxDuckingEnabled) {
            if (duckFactor != 1.0f) {
                duckFactor = 1.0f;
            }
            if (enginePausedByDuck) {
                enginePausedByDuck = false;
                WavPlayer.resume();
            }
            applyGain(client, mixin);
            return;
        }

        if (--scanCountdown <= 0) {
            scanCountdown = SCAN_INTERVAL_TICKS;
            jukeboxNearby = isPlayingJukeboxNearby(client, config.jukeboxDetectionRadius);
        }

        float target = jukeboxNearby ? 0.0f : 1.0f;
        float durationTicks = (float) Math.max(1.0, config.jukeboxDuckFadeSeconds) * 20f;
        float step = 1.0f / durationTicks;

        if (duckFactor < target) {
            if (enginePausedByDuck) {
                enginePausedByDuck = false;
                WavPlayer.resume();
            }
            duckFactor = Math.min(target, duckFactor + step);
        } else if (duckFactor > target) {
            duckFactor = Math.max(target, duckFactor - step);
        }

        if (duckFactor <= 0.0f) {
            duckFactor = 0.0f;
            if (!enginePausedByDuck) {
                enginePausedByDuck = true;
                WavPlayer.pause();
            }
        }

        applyGain(client, mixin);
    }

    private static void applyGain(Minecraft client, IMusicManagerMixin mixin) {
        float sliderVolume = client.options.getSoundSourceVolume(SoundSource.MUSIC);
        mixin.mdr$setGain(sliderVolume * duckFactor);
        WavPlayer.setDuckMultiplier(duckFactor);
    }

    private static boolean isPlayingJukeboxNearby(Minecraft client, int radius) {
        if (client.player == null || playingJukeboxes.isEmpty()) return false;

        BlockPos playerPos = client.player.blockPosition();
        double radiusSq = (double) radius * radius;

        for (BlockPos pos : playingJukeboxes) {
            if (pos.distSqr(playerPos) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    // Вызывать из restartForWorldJoin()/resetPlaybackState() — иначе при заходе в новый мир
    // duckFactor/пауза/список играющих проигрывателей могли бы остаться зависшими с прошлой сессии.
    public static void reset() {
        duckFactor = 1.0f;
        scanCountdown = 0;
        jukeboxNearby = false;
        enginePausedByDuck = false;
        playingJukeboxes.clear();
    }
}