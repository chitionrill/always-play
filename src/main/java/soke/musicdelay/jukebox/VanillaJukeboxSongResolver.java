package soke.musicdelay.jukebox;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.Level;

// Находит Holder<JukeboxSong> по id самой записи в реестре игры (например "minecraft:5") — тот
// самый id, что клиент теперь отправляет для настоящих "дисков" (см. RecordMetadataScreen).
// Точное совпадение по ключу реестра, без угадывания по звуковым файлам.
public class VanillaJukeboxSongResolver {

    public static Holder<JukeboxSong> findById(Level level, Identifier songId) {
        var registryOpt = level.registryAccess().lookup(Registries.JUKEBOX_SONG);
        if (registryOpt.isEmpty()) return null;

        var registry = registryOpt.get();
        ResourceKey<JukeboxSong> key = ResourceKey.create(Registries.JUKEBOX_SONG, songId);
        JukeboxSong song = registry.getValue(key);
        if (song == null) return null;

        return registry.wrapAsHolder(song);
    }
}