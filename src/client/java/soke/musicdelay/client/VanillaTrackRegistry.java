package soke.musicdelay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.JukeboxSong;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class VanillaTrackRegistry {

    public record VanillaEntry(Sound sound, Component displayName) {}

    private static List<VanillaEntry> cachedAmbient = null;
    private static List<VanillaEntry> cachedDiscs = null;
    // Обратная связь "файл диска -> id самой записи JukeboxSong в реестре игры". Нужна отдельно
    // от VanillaEntry, потому что плейлисты (Playlist.PlaylistEntry) хранят путь к файлу, а
    // JukeboxSongPlayer.play(...) для кастомных пластинок требует именно Holder<JukeboxSong>.
    private static java.util.Map<Identifier, Identifier> discSoundLocationToSongId = new java.util.HashMap<>();

    public static List<VanillaEntry> getAmbientTracks() {
        if (cachedAmbient == null) refresh();
        return cachedAmbient;
    }

    public static List<VanillaEntry> getDiscTracks() {
        if (cachedDiscs == null) refresh();
        return cachedDiscs;
    }

    public static void refresh() {
        var soundManager = Minecraft.getInstance().getSoundManager();

        List<VanillaEntry> ambientResult = new ArrayList<>();
        LinkedHashSet<String> seenLocations = new LinkedHashSet<>();

        for (Identifier id : soundManager.getAvailableSounds()) {
            if (!id.getNamespace().equals("minecraft")) continue;
            if (!id.getPath().startsWith("music.") && !id.getPath().equals("music")) continue;

            WeighedSoundEvents event = soundManager.getSoundEvent(id);
            if (!(event instanceof IWeighedSoundEventsMixin mixin)) continue;

            for (Sound sound : mixin.mdr$getAllSounds()) {
                String loc = sound.getLocation().toString();
                if (seenLocations.add(loc)) {
                    Component name = Component.translatable(sound.getLocation().toShortLanguageKey().replace("/", "."));
                    ambientResult.add(new VanillaEntry(sound, name));
                }
            }
        }
        cachedAmbient = ambientResult;

        List<VanillaEntry> discsResult = new ArrayList<>();
        java.util.Map<Identifier, Identifier> discMap = new java.util.HashMap<>();
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            client.level.registryAccess().lookup(Registries.JUKEBOX_SONG).ifPresent(registry ->
                    registry.listElements().forEach(holder -> {
                        JukeboxSong song = holder.value();
                        Holder<SoundEvent> soundEventHolder = song.soundEvent();
                        Identifier location = soundEventHolder.value().location();
                        WeighedSoundEvents discEvent = soundManager.getSoundEvent(location);
                        if (!(discEvent instanceof IWeighedSoundEventsMixin discMixin)) return;

                        Identifier songId = holder.key().identifier();
                        for (Sound sound : discMixin.mdr$getAllSounds()) {
                            discsResult.add(new VanillaEntry(sound, song.description()));
                            discMap.put(sound.getLocation(), songId);
                        }
                    })
            );
        }
        cachedDiscs = discsResult;
        discSoundLocationToSongId = discMap;
    }

    // Возвращает id записи JukeboxSong в реестре игры (например "minecraft:5") для файла диска,
    // сохранённого через toPlaylistEntry()/BrowsableTrack — нужно, чтобы позже проиграть именно
    // эту песню через JukeboxSongPlayer.play(...), которому нужен Holder<JukeboxSong>, а не файл.
    public static Identifier getJukeboxSongIdForSoundLocation(Identifier soundLocation) {
        if (cachedDiscs == null) refresh();
        return discSoundLocationToSongId.get(soundLocation);
    }
    // Находит настоящее отображаемое название по идентификатору звука — учитывает и обычную
// музыку (через перевод игры), и пластинки (через их собственное description)
    public static Component getDisplayNameForLocation(Identifier soundLocation) {
        if (cachedDiscs == null) refresh();
        for (VanillaEntry entry : cachedDiscs) {
            if (entry.sound().getLocation().equals(soundLocation)) {
                return entry.displayName();
            }
        }
        return Component.translatable(soundLocation.toShortLanguageKey().replace("/", "."));
    }

    // Находит объект Sound по его идентификатору — нужно чтобы восстановить ванильный
// трек из сохранённого плейлиста (там хранится только строка-идентификатор)
    public static Sound findSoundByLocation(Identifier location) {
        if (cachedAmbient == null) refresh();
        for (VanillaEntry entry : cachedAmbient) {
            if (entry.sound().getLocation().equals(location)) return entry.sound();
        }
        for (VanillaEntry entry : cachedDiscs) {
            if (entry.sound().getLocation().equals(location)) return entry.sound();
        }
        return null;
    }
}