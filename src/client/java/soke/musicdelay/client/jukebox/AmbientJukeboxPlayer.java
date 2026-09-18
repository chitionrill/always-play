package soke.musicdelay.client.jukebox;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import soke.musicdelay.MusicDelayReducer;
import soke.musicdelay.client.AudioTrack;

/** Resolves resources only; audio is owned by the mod's positional player. */
public class AmbientJukeboxPlayer {
    public static void startAt(BlockPos pos, String soundLocation) {
        try {
            // Old records contain an event id, which cannot identify the selected file.
            if (!soundLocation.startsWith("track:")) {
                MusicDelayReducer.LOGGER.warn("Re-record this legacy ambient disc: {}", soundLocation);
                return;
            }
            Identifier location = Identifier.parse(soundLocation.substring("track:".length()));
            Identifier resourceId = Identifier.fromNamespaceAndPath(location.getNamespace(),
                    "sounds/" + location.getPath() + ".ogg");
            var resource = Minecraft.getInstance().getResourceManager().getResource(resourceId);
            if (resource.isEmpty()) {
                MusicDelayReducer.LOGGER.warn("Missing jukebox audio resource: {}", resourceId);
                return;
            }
            // The player takes ownership and closes the decoder after playback.
            PositionalCustomTrackPlayer.startAt(pos, AudioTrack.open(resource.get().open()));
        } catch (Exception e) {
            MusicDelayReducer.LOGGER.error("Cannot open jukebox resource: " + soundLocation, e);
        }
    }

    public static void stop(BlockPos pos) { PositionalCustomTrackPlayer.stop(pos); }

    // Shared player is already stopped and ticked by MusicDelayReducerClient.
    public static void stopAll() { }
    public static void tick(Minecraft client) { }
}