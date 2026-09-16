package soke.musicdelay.client.jukebox;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;
import soke.musicdelay.client.AudioTrack;
import soke.musicdelay.client.playback.JukeboxDuckController;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Path;

// Позиционное (по расстоянию и стороне относительно взгляда игрока) воспроизведение СВОЕГО
// аудиофайла (WAV/MP3/OGG/FLAC) из блока проигрывателя пластинок. Отдельно от основного
// WavPlayer — тот играет "вездесуще", без привязки к точке в мире. Переиспользует существующий
// декодер AudioTrack, но со своей отдельной линией вывода (без кроссфейда/микса — здесь всегда
// только один файл).
//
// Панорама (лево/право) считается только для стерео-файлов — для моно применяется только
// громкость по дистанции, панорамировать нечего.
//
// Упрощения v1: не более одного одновременно играющего кастомного трека на клиент, без
// зацикливания — доиграл, значит доиграл, как в реальности (пластинка остаётся в блоке до
// ручного вытаскивания).
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
    private volatile float targetPan = 0f; // -1 = полностью слева, 0 = по центру, 1 = полностью справа
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

        // Каждый раз, когда мы заново достоверно убедились, что пластинка на месте (в т.ч. после
        // возврата из выгруженного чанка), на всякий случай заново регистрируем позицию в
        // JukeboxDuckController — идемпотентно (просто добавление в Set), но подстраховывает от
        // любой причины, по которой запись могла выпасть из списка, пока чанк был выгружен.
        JukeboxDuckController.onJukeboxSoundStart(player.pos);
        player.paused = false;

        double distance = Math.sqrt(player.pos.distSqr(client.player.blockPosition()));
        float gain = distance >= detectionRadius ? 0f : (float) (1.0 - (distance / detectionRadius));
        player.targetGain = gain;

        // Панорама: угол между направлением взгляда игрока и направлением на блок. 0° — блок
        // прямо по курсу (по центру), ±90° — строго сбоку (полностью в одном ухе).
        double dx = (player.pos.getX() + 0.5) - client.player.getX();
        double dz = (player.pos.getZ() + 0.5) - client.player.getZ();
        double angleToTarget = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = Mth.wrapDegrees(angleToTarget - client.player.getYRot());
        player.targetPan = (float) Math.sin(Math.toRadians(relative));
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

            if (track.getChannels() == 2) {
                // Стерео — применяем и громкость, и панораму. Constant-power pan law: при pan=0
                // оба канала на полной громкости, при уходе в сторону один канал плавно растёт
                // до максимума, другой падает до нуля (без резкого провала суммарной громкости
                // посередине, как было бы при простом линейном пане).
                float pan = targetPan;
                double angle = (pan + 1.0) * (Math.PI / 4.0); // 0..PI/2
                float leftMul = gain * (float) Math.cos(angle);
                float rightMul = gain * (float) Math.sin(angle);

                for (int i = 0; i + 3 < read; i += 4) {
                    int left = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                    int right = (short) ((buffer[i + 2] & 0xFF) | (buffer[i + 3] << 8));
                    left = clamp16(Math.round(left * leftMul));
                    right = clamp16(Math.round(right * rightMul));
                    buffer[i] = (byte) (left & 0xFF);
                    buffer[i + 1] = (byte) ((left >> 8) & 0xFF);
                    buffer[i + 2] = (byte) (right & 0xFF);
                    buffer[i + 3] = (byte) ((right >> 8) & 0xFF);
                }
            } else {
                // Моно — панорамировать нечего, только громкость по дистанции.
                for (int i = 0; i + 1 < read; i += 2) {
                    int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                    sample = clamp16(Math.round(sample * gain));
                    buffer[i] = (byte) (sample & 0xFF);
                    buffer[i + 1] = (byte) ((sample >> 8) & 0xFF);
                }
            }

            line.write(buffer, 0, read);
        }

        line.drain();
        line.close();
        track.close();
        finishCleanup();
    }

    private static int clamp16(int sample) {
        return Math.max(-32768, Math.min(32767, sample));
    }

    private void finishCleanup() {
        if (active == this) {
            active = null;
            JukeboxDuckController.onJukeboxSoundStop(pos);
        }
    }
}