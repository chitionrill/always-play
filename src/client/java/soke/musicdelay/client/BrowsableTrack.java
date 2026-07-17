package soke.musicdelay.client;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

public class BrowsableTrack {

    public enum Kind { HEADER, AMBIENT, DISC, CUSTOM }

    public final Kind kind;
    public final Sound vanillaSound;
    public final Path customPath;
    public final Component displayName;

    private BrowsableTrack(Kind kind, Sound vanillaSound, Path customPath, Component displayName) {
        this.kind = kind;
        this.vanillaSound = vanillaSound;
        this.customPath = customPath;
        this.displayName = displayName;
    }

    public static BrowsableTrack header(Component label) {
        return new BrowsableTrack(Kind.HEADER, null, null, label);
    }

    public static BrowsableTrack ambient(VanillaTrackRegistry.VanillaEntry entry) {
        return new BrowsableTrack(Kind.AMBIENT, entry.sound(), null, entry.displayName());
    }

    public static BrowsableTrack disc(VanillaTrackRegistry.VanillaEntry entry) {
        return new BrowsableTrack(Kind.DISC, entry.sound(), null, entry.displayName());
    }

    public static BrowsableTrack custom(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return new BrowsableTrack(Kind.CUSTOM, null, path, Component.literal(name));
    }

    public boolean isPlayable() {
        return kind != Kind.HEADER;
    }

    public UnifiedTrack toUnifiedTrack() {
        if (kind == Kind.CUSTOM) return UnifiedTrack.ofCustom(customPath);
        return UnifiedTrack.ofVanilla(vanillaSound);
    }

    public Playlist.PlaylistEntry toPlaylistEntry() {
        if (kind == Kind.CUSTOM) return Playlist.PlaylistEntry.ofCustom(customPath.toString());
        return Playlist.PlaylistEntry.ofVanilla(vanillaSound.getLocation().toString());
    }

    // Собирает полный список: фоновая музыка → разделитель "Пластинки" → пластинки → разделитель "Свои треки" → кастомные
    public static java.util.List<BrowsableTrack> buildFullList() {
        java.util.List<BrowsableTrack> result = new java.util.ArrayList<>();

        for (VanillaTrackRegistry.VanillaEntry entry : VanillaTrackRegistry.getAmbientTracks()) {
            result.add(ambient(entry));
        }

        java.util.List<VanillaTrackRegistry.VanillaEntry> discs = VanillaTrackRegistry.getDiscTracks();
        if (!discs.isEmpty()) {
            result.add(header(Component.translatable("music-delay-reducer.browser.discs_header")));
            for (VanillaTrackRegistry.VanillaEntry entry : discs) {
                result.add(disc(entry));
            }
        }

        java.util.List<Path> customTracks = CustomTrackManager.get().getTracks();
        if (!customTracks.isEmpty()) {
            result.add(header(Component.translatable("music-delay-reducer.browser.custom_header")));
            for (Path path : customTracks) {
                result.add(custom(path));
            }
        }

        return result;
    }
}