package soke.musicdelay.client.jukebox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import soke.musicdelay.client.playback.JukeboxDuckController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Проигрывание обычной (не диск) ванильной фоновой музыки из блока проигрывателя пластинок —
// через SimpleSoundInstance.forJukeboxSong(...), тот же самый способ, которым игра создаёт звук
// для настоящих пластинок, поэтому позиционирование (громкость по расстоянию, панорама)
// получается идентичным. В отличие от кастомных файлов не нужен свой аудио-движок и не нужно
// самим отслеживать окончание трека — движок сам знает длительность файла и сам его
// останавливает; мы только следим за этим через isActive(), чтобы вовремя снять регистрацию в
// JukeboxDuckController.
public class AmbientJukeboxPlayer {

    private static final int SCAN_INTERVAL_TICKS = 5;

    private static final Map<BlockPos, SoundInstance> activeByPos = new ConcurrentHashMap<>();
    private static int scanCountdown = 0;

    public static void startAt(BlockPos pos, String soundEventId) {
        stop(pos);

        SoundEvent soundEvent = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(soundEventId));
        if (soundEvent == null) return;

        BlockPos immutablePos = pos.immutable();
        Vec3 center = new Vec3(immutablePos.getX() + 0.5, immutablePos.getY() + 0.5, immutablePos.getZ() + 0.5);
        SoundInstance instance = SimpleSoundInstance.forJukeboxSong(soundEvent, center);

        Minecraft.getInstance().getSoundManager().play(instance);
        activeByPos.put(immutablePos, instance);
        JukeboxDuckController.onJukeboxSoundStart(immutablePos);
    }

    public static void stop(BlockPos pos) {
        SoundInstance instance = activeByPos.remove(pos.immutable());
        if (instance == null) return;
        Minecraft.getInstance().getSoundManager().stop(instance);
        JukeboxDuckController.onJukeboxSoundStop(pos.immutable());
    }

    public static void stopAll() {
        for (BlockPos pos : activeByPos.keySet()) {
            stop(pos);
        }
    }

    public static void tick(Minecraft client) {
        if (activeByPos.isEmpty()) return;
        if (--scanCountdown > 0) return;
        scanCountdown = SCAN_INTERVAL_TICKS;

        if (client.level == null) {
            stopAll();
            return;
        }

        for (Map.Entry<BlockPos, SoundInstance> entry : Map.copyOf(activeByPos).entrySet()) {
            BlockPos pos = entry.getKey();
            SoundInstance instance = entry.getValue();

            if (!client.getSoundManager().isActive(instance)) {
                // Трек доиграл до конца сам — движок это уже знает лучше нас.
                activeByPos.remove(pos);
                JukeboxDuckController.onJukeboxSoundStop(pos);
                continue;
            }

            if (!client.level.isLoaded(pos)) {
                // Чанк не загружен — не можем проверить состояние блока. Реальный звуковой
                // движок сам продолжает считать время в реальном времени, как настоящая
                // пластинка, поэтому дальше ничего не трогаем.
                continue;
            }

            BlockState state = client.level.getBlockState(pos);
            if (!state.is(Blocks.JUKEBOX) || !state.hasProperty(JukeboxBlock.HAS_RECORD) || !state.getValue(JukeboxBlock.HAS_RECORD)) {
                stop(pos);
                continue;
            }

            // Пластинка всё ещё на месте и реально играет — переподтверждаем регистрацию в
            // JukeboxDuckController на случай, если запись могла выпасть из его списка.
            JukeboxDuckController.onJukeboxSoundStart(pos);
        }
    }
}