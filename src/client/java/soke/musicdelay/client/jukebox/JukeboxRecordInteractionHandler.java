package soke.musicdelay.client.jukebox;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import soke.musicdelay.client.gui.RecordTrackChooserScreen;
import soke.musicdelay.jukebox.SharedJukeboxServer;
import soke.musicdelay.jukebox.CustomRecordData;
/** Only UI belongs on the client; insertion is server-authoritative. */
public class JukeboxRecordInteractionHandler {
    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !level.getBlockState(hit.getBlockPos()).is(Blocks.JUKEBOX)) return InteractionResult.PASS;
            var held = player.getMainHandItem();
            if (!SharedJukeboxServer.clean(held) && !CustomRecordData.isPresent(held)) return InteractionResult.PASS;
            if (!SharedJukeboxClient.supported()) {
                SharedJukeboxClient.message("server_required"); return InteractionResult.FAIL;
            }
            if (SharedJukeboxServer.clean(held)) Minecraft.getInstance().gui.setScreen(new RecordTrackChooserScreen(null));
            return InteractionResult.SUCCESS;
        });
    }
}
