package soke.musicdelay.client;

import net.minecraft.client.resources.sounds.Sound;

import java.nio.file.Path;

public class UnifiedTrack {

    public enum Type { VANILLA, CUSTOM }

    public final Type type;
    public final Sound vanillaSound; // заполнено только если type == VANILLA
    public final Path customPath;    // заполнено только если type == CUSTOM

    private UnifiedTrack(Type type, Sound vanillaSound, Path customPath) {
        this.type = type;
        this.vanillaSound = vanillaSound;
        this.customPath = customPath;
    }

    public static UnifiedTrack ofVanilla(Sound sound) {
        return new UnifiedTrack(Type.VANILLA, sound, null);
    }

    public static UnifiedTrack ofCustom(Path path) {
        return new UnifiedTrack(Type.CUSTOM, null, path);
    }

    public String getDisplayName() {
        if (type == Type.CUSTOM) {
            String name = customPath.getFileName().toString();
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        } else {
            return vanillaSound.getLocation().toString();
        }
    }
}