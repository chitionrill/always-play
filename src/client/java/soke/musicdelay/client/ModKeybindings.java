package soke.musicdelay.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class ModKeybindings {

    public static KeyMapping skipForward;
    public static KeyMapping skipBackward;
    public static KeyMapping volumeUp;
    public static KeyMapping volumeDown;

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("music-delay-reducer", "general"));

    public static void register() {
        skipForward = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.music-delay-reducer.skip_forward",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_RIGHT,
                CATEGORY
        ));

        skipBackward = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.music-delay-reducer.skip_backward",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_LEFT,
                CATEGORY
        ));

        volumeUp = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.music-delay-reducer.volume_up",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_UP,
                CATEGORY
        ));

        volumeDown = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.music-delay-reducer.volume_down",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_DOWN,
                CATEGORY
        ));
    }
}