package soke.musicdelay.client.speaker;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import soke.musicdelay.client.AudioTrack;
import soke.musicdelay.client.speaker.audio.*;
import soke.musicdelay.speaker.SpeakerPlayback;
import soke.musicdelay.speaker.SpeakerRegistry;

/** Client-thread controller. Audio workers receive immutable levels, never world objects. */
public final class SpeakerWorldAudio {
    private static final Map<UUID,Emitter> sources=new HashMap<>();
    private static final List<SpeakerVoice> closing=new ArrayList<>();
    private static long ticks;
    private static Object world;
    private static final int MAX_VOICES=8;
    private static final class Emitter {
        SpeakerPlayback.Snapshot state;
        long seen;
        SpeakerVoice voice;
        boolean ended;
        Emitter(SpeakerPlayback.Snapshot s) { state=s; }
    }
    private SpeakerWorldAudio() { }
    public static void receive(SpeakerPlayback.Snapshot packet) {
        var mc=Minecraft.getInstance();
        if (!mc.hasSingleplayerServer() || mc.level==null) return;
        if (world!=mc.level) { clear(); world=mc.level; }
        var e=sources.computeIfAbsent(packet.id(),id->new Emitter(packet));
        if (!e.state.track().equals(packet.track()) || Math.abs(e.state.epoch()-packet.epoch())>150 || !packet.enabled()) {
            stop(e); e.ended=false;
        }
        e.state=packet; e.seen=ticks;
    }
    private static boolean terminal(SpeakerVoice v) {
        return v.status()==SpeakerVoice.Status.STOPPED || v.status()==SpeakerVoice.Status.FINISHED || v.status()==SpeakerVoice.Status.FAILED;
    }
    private static void stop(Emitter e) {
        if(e.voice!=null) { e.voice.close(); closing.add(e.voice); e.voice=null; }
    }
    public static void clear() {
        sources.values().forEach(SpeakerWorldAudio::stop); sources.clear(); world=null;
    }
    private static boolean carried(Minecraft mc,UUID id) {
        for(int i=0;i<mc.player.getInventory().getContainerSize();i++) {
            var item=mc.player.getInventory().getItem(i);
            if(item.is(SpeakerRegistry.ITEM) && soke.musicdelay.speaker.SpeakerData.read(item).map(s->s.id().equals(id)).orElse(false))return true;
        }
        return false;
    }
    public static void tick(Minecraft mc) {
        closing.removeIf(SpeakerWorldAudio::terminal);
        if(mc.level==null || mc.player==null || !mc.hasSingleplayerServer() || !mc.player.isAlive()) { clear(); return; }
        if(world!=mc.level) { clear(); world=mc.level; }
        if(!mc.isPaused()) ticks++;
        if(ticks%20==0) SpeakerTracks.poll();
        var it=sources.values().iterator();
        while(it.hasNext()) {
            Emitter e=it.next(); var s=e.state;
            boolean inInventory=carried(mc,s.id());
            boolean blockPresent=!s.carried() && mc.level.isLoaded(s.pos()) && mc.level.getBlockState(s.pos()).is(SpeakerRegistry.BLOCK);
            // Brief grace covers the inventory/block handoff, preserving the open decoder and song position.
            if(ticks-e.seen>30 || (!inInventory && !blockPresent && ticks-e.seen>6)) {
                stop(e); it.remove(); continue;
            }
            double dx=s.pos().getX()+.5-mc.player.getX(),dy=s.pos().getY()+.5-mc.player.getEyeY(),dz=s.pos().getZ()+.5-mc.player.getZ();
            double distance=inInventory?0:Math.sqrt(dx*dx+dy*dy+dz*dz);
            if(!s.enabled() || s.track().isEmpty() || distance>32) { stop(e); continue; }
            var track=SpeakerTracks.find(s.track());
            if(track==null) continue;
            if(e.ended) {
                if(ticks%10==0 && ClientPlayNetworking.canSend(SpeakerPlayback.End.TYPE))
                    ClientPlayNetworking.send(new SpeakerPlayback.End(s.pos(),s.id(),s.track(),s.epoch()));
                continue;
            }
            if(e.voice==null) {
                long count=sources.values().stream().filter(v->v.voice!=null).count()+closing.size();
                if(count>=MAX_VOICES) continue;
                long offset=Math.max(0,s.offset()+(ticks-e.seen)*50);
                if(offset>86_400_000) { e.ended=true; continue; }
                e.voice=new SpeakerVoice(s.id(),()->AudioTrack.open(track.path()),offset);
                e.voice.start();
            }
            var status=e.voice.status();
            if(status==SpeakerVoice.Status.FAILED || status==SpeakerVoice.Status.FINISHED) {
                e.ended=true;
                if(status==SpeakerVoice.Status.FAILED) {
                    soke.musicdelay.MusicDelayReducer.LOGGER.warn("Speaker audio failed: {}",s.id(),e.voice.failure());
                    mc.player.sendOverlayMessage(Component.literal("Не удалось проиграть трек колонки. Проверь файл и аудиоустройство."));
                }
                if(ClientPlayNetworking.canSend(SpeakerPlayback.End.TYPE))
                    ClientPlayNetworking.send(new SpeakerPlayback.End(s.pos(),s.id(),s.track(),s.epoch()));
                stop(e); continue;
            }
            double yaw=Math.toRadians(mc.player.getYRot()),horizontal=Math.hypot(dx,dz);
            double pan=horizontal<.001?0:(-dx*Math.cos(yaw)-dz*Math.sin(yaw))/horizontal;
            var levels=SpeakerPcm.spatial(distance,pan,s.volume(),24,
                    mc.options.getSoundSourceVolume(SoundSource.MASTER),mc.options.getSoundSourceVolume(SoundSource.RECORDS),inInventory);
            e.voice.update(new SpeakerVoice.Control(levels,mc.isPaused()));
        }
    }
}
