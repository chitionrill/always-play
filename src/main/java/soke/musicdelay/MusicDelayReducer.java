package soke.musicdelay;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soke.musicdelay.jukebox.SharedJukeboxServer;
public class MusicDelayReducer implements ModInitializer {
    public static final String MOD_ID = "music-delay-reducer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    @Override public void onInitialize() { SharedJukeboxServer.register(); }
}
