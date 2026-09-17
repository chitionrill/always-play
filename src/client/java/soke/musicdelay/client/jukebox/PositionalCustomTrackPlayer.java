package soke.musicdelay.client.jukebox;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Позиционное (по расстоянию и стороне относительно взгляда игрока) воспроизведение СВОЕГО
// аудиофайла (WAV/MP3/OGG/FLAC) из блока проигрывателя пластинок. Отдельно от основного
// WavPlayer — тот играет "вездесуще", без привязки к точке в мире. Переиспользует существующий
// декодер AudioTrack, но со своей отдельной линией вывода на каждый проигрыватель (без
// кроссфейда/микса — у каждого экземпляра всегда только один файл).
//
// Поддерживает несколько одновременно играющих кастомных пластинок — по одному экземпляру этого
// класса на позицию блока, в карте activeByPos.
//
// Панорама (лево/право) считается только для стерео-файлов — для моно применяется только
// громкость по дистанции, панорамировать нечего.
//
// Без зацикливания — доиграл, значит доиграл, как в реальности (пластинка остаётся в блоке до
// ручного вытаскивания).
public class PositionalCustomTrackPlayer {

    private static final int SCAN_INTERVAL_TICKS = 1;
    private static final int BUFFER_FRAMES = 1024;
    // Максимальный уход панорамы в сторону (1.0 = полностью в одном ухе, тише не бывает).
    // 0.85 оставляет дальнему уху заметный, но приглушённый уровень даже строго сбоку.
    private static final float MAX_PAN = 0.85f;

    private static final Map<BlockPos, PositionalCustomTrackPlayer> activeByPos = new ConcurrentHashMap<>();

    private final BlockPos pos;
    private final AudioTrack track;
    private final SourceDataLine line;
    private final Thread thread;
    private volatile boolean stopRequested = false;
    private volatile boolean paused = false;
    private volatile float targetGain = 1.0f;
    private volatile float targetPan = 0f; // -1 = полностью слева, 0 = по центру, 1 = полностью справа
    private float currentGain = 1.0f; // используется только внутри runLoop — плавная интерполяция к targetGain
    private float currentPan = 0f;    // используется только внутри runLoop — плавная интерполяция к targetPan
    private int scanCountdown = 0;

    private PositionalCustomTrackPlayer(BlockPos pos, AudioTrack track, SourceDataLine line) {
        this.pos = pos.immutable();
        this.track = track;
        this.line = line;
        this.thread = new Thread(this::runLoop, "mdr-jukebox-audio-" + pos.getX() + "-" + pos.getY() + "-" + pos.getZ());
        this.thread.setDaemon(true);
    }

    public static void startAt(BlockPos pos, Path filePath) {
        stop(pos); // если на этой позиции уже что-то играло — заменяем

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
        activeByPos.put(player.pos, player);
        JukeboxDuckController.onJukeboxSoundStart(player.pos);
        player.thread.start();
    }

    public static void stop(BlockPos pos) {
        PositionalCustomTrackPlayer player = activeByPos.remove(pos.immutable());
        if (player == null) return;
        player.stopRequested = true;
        JukeboxDuckController.onJukeboxSoundStop(player.pos);
    }

    // Вызывать при отключении от мира/сервера — останавливает все играющие кастомные пластинки
    // сразу, а не только одну.
    public static void stopAll() {
        for (BlockPos pos : activeByPos.keySet()) {
            stop(pos);
        }
    }

    // Вызывать из общего клиентского тик-лупа — пересчитывает громкость/панораму по дистанции для
    // каждого играющего экземпляра и следит, не вытащили ли соответствующую пластинку/не сломали
    // ли блок (доигрывание файла до конца отслеживает сам поток воспроизведения — см.
    // finishCleanup()).
    public static void tick(Minecraft client, int detectionRadius) {
        if (activeByPos.isEmpty()) return;
        if (client.level == null || client.player == null) {
            stopAll();
            return;
        }

        for (PositionalCustomTrackPlayer player : activeByPos.values()) {
            player.tickOne(client, detectionRadius);
        }
    }

    private void tickOne(Minecraft client, int detectionRadius) {
        if (--scanCountdown > 0) return;
        scanCountdown = SCAN_INTERVAL_TICKS;

        if (!client.level.isLoaded(pos)) {
            // Чанк не загружен — мы не можем достоверно узнать, стоит ли ещё пластинка в блоке.
            // Считаем, что стоит, и просто ставим воспроизведение на паузу вместо полной
            // остановки — трек не сбрасывается и не теряет место при возвращении игрока.
            paused = true;
            return;
        }

        BlockState state = client.level.getBlockState(pos);
        if (!state.is(Blocks.JUKEBOX) || !state.hasProperty(JukeboxBlock.HAS_RECORD) || !state.getValue(JukeboxBlock.HAS_RECORD)) {
            // Чанк загружен, и мы точно видим, что пластинки нет/это не проигрыватель —
            // здесь уже действительно останавливаем (это не про выгрузку, а про реальное
            // вытаскивание/поломку блока).
            stop(pos);
            return;
        }

        // Каждый раз, когда мы заново достоверно убедились, что пластинка на месте (в т.ч. после
        // возврата из выгруженного чанка), на всякий случай заново регистрируем позицию в
        // JukeboxDuckController — идемпотентно (просто добавление в Set), но подстраховывает от
        // любой причины, по которой запись могла выпасть из списка, пока чанк был выгружен.
        JukeboxDuckController.onJukeboxSoundStart(pos);
        paused = false;

        // Затухание по дистанции: не прямая линия, а квадратичная кривая — громкость держится
        // высокой большую часть радиуса и падает быстрее только ближе к его краю, это ближе к
        // тому, как звук воспринимается на слух в реальности.
        double distance = Math.sqrt(pos.distSqr(client.player.blockPosition()));
        double normalizedDistance = Math.min(1.0, distance / detectionRadius);
        float distanceGain = (float) (1.0 - normalizedDistance * normalizedDistance);

        // Игровые настройки громкости ("Пластинки" + "Общая") должны действовать на этот трек
        // так же, как на любой другой звук в игре — сам вывод у нас отдельный от движка, поэтому
        // применяем эти множители вручную.
        float settingsVolume = client.options.getSoundSourceVolume(SoundSource.MASTER)
                * client.options.getSoundSourceVolume(SoundSource.RECORDS);

        targetGain = distanceGain * settingsVolume;

        // Панорама: угол между направлением взгляда игрока и направлением на блок. 0° — блок
        // прямо по курсу (по центру). Ограничиваем максимальный уход в сторону — в реальности
        // даже строго сбоку звук слышен тише, а не полностью пропадает в одном ухе.
        double dx = (pos.getX() + 0.5) - client.player.getX();
        double dz = (pos.getZ() + 0.5) - client.player.getZ();
        double angleToTarget = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = Mth.wrapDegrees(angleToTarget - client.player.getYRot());
        float rawPan = (float) Math.sin(Math.toRadians(relative));
        targetPan = Math.max(-MAX_PAN, Math.min(MAX_PAN, rawPan));
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

            boolean stereo = track.getChannels() == 2;
            int bytesPerFrame = stereo ? 4 : 2;
            int frames = read / bytesPerFrame;

            float startGain = currentGain;
            float endGain = targetGain;
            float startPan = currentPan;
            float endPan = targetPan;

            for (int frame = 0; frame < frames; frame++) {
                float t = frames <= 1 ? 1f : (float) frame / (frames - 1);
                float g = startGain + (endGain - startGain) * t;
                int i = frame * bytesPerFrame;

                if (stereo) {
                    // Constant-power pan law: при pan=0 оба канала на полной громкости, при уходе
                    // в сторону один канал растёт, другой снижается — но не до нуля, так как pan
                    // ограничен константой MAX_PAN.
                    float pan = startPan + (endPan - startPan) * t;
                    double angle = (pan + 1.0) * (Math.PI / 4.0); // 0..PI/2
                    float leftMul = g * (float) Math.cos(angle);
                    float rightMul = g * (float) Math.sin(angle);

                    int left = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                    int right = (short) ((buffer[i + 2] & 0xFF) | (buffer[i + 3] << 8));
                    left = clamp16(Math.round(left * leftMul));
                    right = clamp16(Math.round(right * rightMul));
                    buffer[i] = (byte) (left & 0xFF);
                    buffer[i + 1] = (byte) ((left >> 8) & 0xFF);
                    buffer[i + 2] = (byte) (right & 0xFF);
                    buffer[i + 3] = (byte) ((right >> 8) & 0xFF);
                } else {
                    int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                    sample = clamp16(Math.round(sample * g));
                    buffer[i] = (byte) (sample & 0xFF);
                    buffer[i + 1] = (byte) ((sample >> 8) & 0xFF);
                }
            }

            currentGain = endGain;
            currentPan = endPan;

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
        if (activeByPos.get(pos) == this) {
            activeByPos.remove(pos);
            JukeboxDuckController.onJukeboxSoundStop(pos);
        }
    }
}