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

        // Фоновая атмосферная музыка
        List<VanillaEntry> ambientResult = new ArrayList<>();
        LinkedHashSet<String> seenLocations = new LinkedHashSet<>();

        for (Identifier id : soundManager.getAvailableSounds()) {
            if (!id.getNamespace().equals("minecraft")) continue;
            if (!id.getPath().startsWith("music/") && !id.getPath().equals("music")) continue;

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

        // Музыкальные пластинки — доступны только когда загружен мир
        List<VanillaEntry> discsResult = new ArrayList<>();
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            client.level.registryAccess().lookup(Registries.JUKEBOX_SONG).ifPresent(registry ->
                    registry.listElements().forEach(holder -> {
                        JukeboxSong song = holder.value();
                        Holder<SoundEvent> soundEventHolder = song.soundEvent();
                        Identifier location = soundEventHolder.value().location();
                        WeighedSoundEvents event = soundManager.getSoundEvent(location);
                        if (!(event instanceof IWeighedSoundEventsMixin mixin)) return;

                        for (Sound sound : mixin.mdr$getAllSounds()) {
                            discsResult.add(new VanillaEntry(sound, song.description()));
                        }
                    })
            );
        }
        cachedDiscs = discsResult;
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