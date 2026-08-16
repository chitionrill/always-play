package soke.musicdelay.client;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Objects;

public class BrowsableTrack {

    public enum Kind { HEADER, AMBIENT, DISC, CUSTOM }

    public final Kind kind;
    public final Sound vanillaSound;
    public final Path customPath;
    public final Component displayName;

    // Заполняются только для заголовков/треков, пришедших из папок (см. folderHeader/folderTrack).
    // Для старых источников (ambient/disc/плоский custom) остаются null/0.
    public final String groupId;
    public final int depth;

    private BrowsableTrack(Kind kind, Sound vanillaSound, Path customPath, Component displayName,
                           String groupId, int depth) {
        this.kind = kind;
        this.vanillaSound = vanillaSound;
        this.customPath = customPath;
        this.displayName = displayName;
        this.groupId = groupId;
        this.depth = depth;
    }

    public static BrowsableTrack header(Component label) {
        return new BrowsableTrack(Kind.HEADER, null, null, label, null, 0);
    }

    public static BrowsableTrack ambient(VanillaTrackRegistry.VanillaEntry entry) {
        return new BrowsableTrack(Kind.AMBIENT, entry.sound(), null, entry.displayName(), null, 0);
    }

    public static BrowsableTrack disc(VanillaTrackRegistry.VanillaEntry entry) {
        return new BrowsableTrack(Kind.DISC, entry.sound(), null, entry.displayName(), null, 0);
    }

    public static BrowsableTrack custom(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return new BrowsableTrack(Kind.CUSTOM, null, path, Component.literal(name), null, 0);
    }

    // Заголовок папки внутри дерева (в т.ч. вложенной). groupId — стабильный путь-ключ
    // (TrackGroup.getId()), используется для collapse/expand вместо текста заголовка,
    // чтобы одноимённые папки в разных местах не схлопывались синхронно.
    public static BrowsableTrack folderHeader(String groupId, int depth, Component label) {
        return new BrowsableTrack(Kind.HEADER, null, null, label, groupId, depth);
    }

    // Трек, найденный сканером внутри папки (в т.ч. вложенной).
    public static BrowsableTrack folderTrack(Path path, String groupId, int depth) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return new BrowsableTrack(Kind.CUSTOM, null, path, Component.literal(name), groupId, depth);
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

        java.util.List<VanillaTrackRegistry.VanillaEntry> ambientTracks = VanillaTrackRegistry.getAmbientTracks();
        if (!ambientTracks.isEmpty()) {
            result.add(header(Component.translatable("music-delay-reducer.browser.ambient_header")));
            for (VanillaTrackRegistry.VanillaEntry entry : ambientTracks) {
                result.add(ambient(entry));
            }
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BrowsableTrack other)) return false;
        if (kind != other.kind) return false;
        if (kind == Kind.CUSTOM) {
            return Objects.equals(customPath, other.customPath);
        }
        if (kind == Kind.HEADER) {
            // Заголовки из дерева папок сравниваем по groupId — так одноимённые папки
            // в разных местах не считаются одним и тем же заголовком. Старые заголовки
            // (Ambient/Discs/Custom) groupId не имеют — для них сравнение по тексту, как раньше.
            if (groupId != null || other.groupId != null) {
                return Objects.equals(groupId, other.groupId);
            }
            return Objects.equals(displayName.getString(), other.displayName.getString());
        }
        // AMBIENT / DISC — сравниваем по локации ванильного трека
        return vanillaSound != null && other.vanillaSound != null
                && Objects.equals(vanillaSound.getLocation(), other.vanillaSound.getLocation());
    }

    @Override
    public int hashCode() {
        if (kind == Kind.CUSTOM) return Objects.hash(kind, customPath);
        if (kind == Kind.HEADER) {
            return groupId != null ? Objects.hash(kind, groupId) : Objects.hash(kind, displayName.getString());
        }
        return Objects.hash(kind, vanillaSound != null ? vanillaSound.getLocation() : null);
    }

}