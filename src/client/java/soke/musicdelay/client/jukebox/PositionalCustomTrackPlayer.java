package soke.musicdelay.client.jukebox;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;
import soke.musicdelay.client.AudioTrack;
import soke.musicdelay.client.playback.JukeboxDuckController;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Path;

// Позиционное (по расстоянию до игрока) воспроизведение СВОЕГО аудиофайла (WAV/MP3/OGG/FLAC) из
// блока проигрывателя пластинок. Отдельно от основного WavPlayer — тот играет "вездесуще", без
// привязки к точке в мире, а тут нужна привязанная к позиции, плавно меняющаяся по дистанции
// громкость. Переиспользует существующий декодер AudioTrack, но со своей отдельной линией вывода
// (без кроссфейда/микса — здесь всегда только один файл).
//
// Упрощения v1: без стереопанорамы по стороне света (только громкость по расстоянию), не более
// одного одновременно играющего кастомного трека на клиент, без зацикливания — доиграл, значит
// доиграл, как в реальности (пластинка остаётся в блоке до ручного вытаскивания).
public class PositionalCustomTrackPlayer {

    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int BUFFER_FRAMES = 2048;

    private static volatile PositionalCustomTrackPlayer active;

    private final BlockPos pos;
    private final AudioTrack track;
    private final SourceDataLine line;
    private final Thread thread;
    private volatile boolean stopRequested = false;
    private volatile boolean paused = false;
    private volatile float targetGain = 1.0f;
    private int scanCountdown = 0;

    private PositionalCustomTrackPlayer(BlockPos pos, AudioTrack track, SourceDataLine line) {
        this.pos = pos;
        this.track = track;
        this.line = line;
        this.thread = new Thread(this::runLoop, "mdr-jukebox-audio");
        this.thread.setDaemon(true);
    }

    public static void startAt(BlockPos pos, Path filePath) {
        stopActive();

        AudioTrack track;
        SourceDataLine line;
        try {
            track = AudioTrack.open(filePath);
            AudioFormat format = new AudioFormat(track.getSampleRate(), 16, track.getChannels(), true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, BUFFER_FRAMES * track.getChannels() * 2 * 4);
            line.start();
        } catch (Exception e) {
            // Не удалось открыть файл/линию — тихо не проигрываем, не ломаем остальное.
            return;
        }

        PositionalCustomTrackPlayer player = new PositionalCustomTrackPlayer(pos, track, line);
        active = player;
        JukeboxDuckController.onJukeboxSoundStart(pos);
        player.thread.start();
    }

    public static void stopActive() {
        PositionalCustomTrackPlayer player = active;
        if (player == null) return;
        active = null;
        player.stopRequested = true;
        JukeboxDuckController.onJukeboxSoundStop(player.pos);
    }

    // Вызывать из общего клиентского тик-лупа — пересчитывает громкость по дистанции и следит,
    // не вытащили ли пластинку/не сломали ли блок (доигрывание файла до конца отслеживает сам
    // поток воспроизведения — см. finishCleanup()).
    public static void tick(Minecraft client, int detectionRadius) {
        PositionalCustomTrackPlayer player = active;
        if (player == null) return;

        if (--player.scanCountdown > 0) return;
        player.scanCountdown = SCAN_INTERVAL_TICKS;

        if (client.level == null || client.player == null) {
            stopActive();
            return;
        }

        if (!client.level.isLoaded(player.pos)) {
            // Чанк не загружен — мы не можем достоверно узнать, стоит ли ещё пластинка в блоке.
            // Считаем, что стоит, и просто ставим воспроизведение на паузу вместо полной
            // остановки — трек не сбрасывается и не теряет место при возвращении игрока.
            player.paused = true;
            return;
        }

        BlockState state = client.level.getBlockState(player.pos);
        if (!state.is(Blocks.JUKEBOX) || !state.hasProperty(JukeboxBlock.HAS_RECORD) || !state.getValue(JukeboxBlock.HAS_RECORD)) {
            // Чанк загружен, и мы точно видим, что пластинки нет/это не проигрыватель —
            // здесь уже действительно останавливаем (это не про выгрузку, а про реальное
            // вытаскивание/поломку блока).
            stopActive();
            return;
        }

        player.paused = false;

        double distance = Math.sqrt(player.pos.distSqr(client.player.blockPosition()));
        float gain = distance >= detectionRadius ? 0f : (float) (1.0 - (distance / detectionRadius));
        player.targetGain = gain;
    }

    private void runLoop() {
        byte[] buffer = new byte[BUFFER_FRAMES * track.getChannels() * 2];
        while (!stopRequested) {
            if (paused) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                continue;
            }

            int read = track.read(buffer);
            if (read < 0) break; // файл доиграл до конца сам по себе

            float gain = targetGain;
            for (int i = 0; i + 1 < read; i += 2) {
                int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                sample = Math.round(sample * gain);
                sample = Math.max(-32768, Math.min(32767, sample));
                buffer[i] = (byte) (sample & 0xFF);
                buffer[i + 1] = (byte) ((sample >> 8) & 0xFF);
            }

            line.write(buffer, 0, read);
        }

        line.drain();
        line.close();
        track.close();
        finishCleanup();
    }

    private void finishCleanup() {
        if (active == this) {
            active = null;
            JukeboxDuckController.onJukeboxSoundStop(pos);
        }
    }
}