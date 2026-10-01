package soke.musicdelay.client.speaker;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import soke.musicdelay.speaker.SpeakerNetworking;

public final class SpeakerClient {
    private SpeakerClient() { }

    public static void register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(SpeakerWorldAudio::tick);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((h,s,c)->{ SpeakerWorldAudio.clear(); SpeakerTracks.refresh(); });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h,c)->SpeakerWorldAudio.clear());
        ClientPlayNetworking.registerGlobalReceiver(soke.musicdelay.speaker.SpeakerPlayback.Snapshot.TYPE,
                (packet, context)->context.client().execute(()->SpeakerWorldAudio.receive(packet)));
        ClientPlayNetworking.registerGlobalReceiver(SpeakerNetworking.Menu.TYPE, (packet, context) ->
                context.client().execute(() -> {
                    var client = context.client();
                    if (client.level == null || client.player == null) return;
                    if (client.gui.screen() instanceof SpeakerScreen screen && screen.accepts(packet.token())) {
                        screen.update(packet);
                    } else if (packet.opening()) client.gui.setScreen(new SpeakerScreen(packet));
                }));
    }
}
