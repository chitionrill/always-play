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
        SpeakerPropagation.Result acoustic=SpeakerPropagation.Result.SILENT;
        long acousticTime=-1;
        double extraPath;
        boolean wasCarried;
        SpeakerEnergy.Transport energyJob;
        SpeakerPropagation.Point energyOrigin,energyListener;
        net.minecraft.core.BlockPos energySource,energyPartner;
        long energyTime=-100;
        List<SpeakerPropagation.Point> route=List.of();
        SpeakerPropagation.Aperture aperture=SpeakerPropagation.Aperture.EMPTY;
        List<SpeakerPropagation.Reflection> reflections=List.of();
        SpeakerPropagation.Job acousticJob;
        net.minecraft.core.BlockPos acousticSource,acousticPartner;
        Emitter(SpeakerPlayback.Snapshot s) { state=s; }
    }
    private SpeakerWorldAudio() { }
    public static void receive(SpeakerPlayback.Snapshot packet) {
        var mc=Minecraft.getInstance();
        if (!mc.hasSingleplayerServer() || mc.level==null) return;
        if (world!=mc.level) { clear(); world=mc.level; }
        var e=sources.computeIfAbsent(packet.id(),id->new Emitter(packet));
        if (!e.state.track().equals(packet.track()) || Math.abs(e.state.epoch()-packet.epoch())>150 || !packet.enabled()) {
            stop(e,!packet.enabled()); e.ended=false;
        }
        e.state=packet; e.seen=ticks;
    }
    private static boolean terminal(SpeakerVoice v) {
        return v.status()==SpeakerVoice.Status.STOPPED || v.status()==SpeakerVoice.Status.FINISHED || v.status()==SpeakerVoice.Status.FAILED;
    }
    private static void stop(Emitter e){stop(e,false);}
    private static void stop(Emitter e,boolean tail) {
        e.energyJob=null;e.energyTime=-100;e.reflections=List.of();
        if(e.voice!=null) { if(tail)e.voice.release();else e.voice.close(); closing.add(e.voice); e.voice=null; }
    }
    public static void clear() {
        sources.values().forEach(SpeakerWorldAudio::stop); sources.clear();closing.forEach(SpeakerVoice::close);world=null;
    }
    private static boolean carried(Minecraft mc,UUID id) {
        var cursor=mc.player.containerMenu.getCarried();
        if(soke.musicdelay.speaker.SpeakerStorage.contains(cursor,id))return true;
        for(int i=0;i<mc.player.getInventory().getContainerSize();i++) {
            var item=mc.player.getInventory().getItem(i);
            if(soke.musicdelay.speaker.SpeakerStorage.contains(item,id))return true;
        }
        return false;
    }
    public static void tick(Minecraft mc) {
        closing.removeIf(SpeakerWorldAudio::terminal);
        if(mc.level==null || mc.player==null || !mc.hasSingleplayerServer()) { clear(); return; }
        if(world!=mc.level) { clear(); world=mc.level; }
        if(!mc.isPaused()) ticks++;
        if(ticks%20==0) SpeakerTracks.poll();
        // Rotate service order so busy scenes do not starve the same emitters.
        long totalDeadline=System.nanoTime()+3_000_000L;
        long acousticDeadline=totalDeadline-750_000L;
        var ordered=new ArrayList<>(sources.values());
        if(!ordered.isEmpty())Collections.rotate(ordered,-(int)(ticks%ordered.size()));
        // Oldest audible update first; rotation above fairly breaks ties.
        ordered.sort(Comparator.comparingLong(e->e.acousticTime));
        var it=ordered.iterator();
        while(it.hasNext()) {
            Emitter e=it.next(); var s=e.state;
            boolean inInventory=s.carried() && mc.player.isAlive() && carried(mc,s.id());
            var dropped=s.itemEntityId()>=0?mc.level.getEntity(s.itemEntityId()):null;
            boolean onGround=dropped instanceof net.minecraft.world.entity.item.ItemEntity item && !item.isRemoved()
                    && soke.musicdelay.speaker.SpeakerStorage.contains(item.getItem(),s.id());
            boolean inChest=s.chest() && mc.level.isLoaded(s.pos())
                    && mc.level.getBlockEntity(s.pos()) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity;
            boolean inShell=s.shell() && !s.carried() && s.itemEntityId()<0 && mc.level.isLoaded(s.pos())
                    && mc.level.getBlockEntity(s.pos()) instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
            boolean inEnder=s.ender() && mc.player.isAlive() && !mc.player.isSpectator() && !s.carried() && mc.level.isLoaded(s.pos())
                    && mc.level.getBlockState(s.pos()).is(net.minecraft.world.level.block.Blocks.ENDER_CHEST);
            boolean blockPresent=s.itemEntityId()<0 && !s.carried() && mc.level.isLoaded(s.pos()) && mc.level.getBlockState(s.pos()).is(SpeakerRegistry.BLOCK);
            // Brief grace covers the inventory/block handoff, preserving the open decoder and song position.
            if(ticks-e.seen>30 || (!inInventory && !blockPresent && !onGround && !inChest && !inShell && !inEnder && ticks-e.seen>6)) {
                stop(e); sources.remove(s.id()); continue;
            }
            // A login snapshot can arrive before the chunk/block entity. Never start dry at a guessed location.
            if(e.voice==null && !inInventory && !blockPresent && !onGround && !inChest && !inShell && !inEnder)continue;
            double sourceX=onGround?dropped.getX():s.pos().getX()+.5;
            double sourceY=onGround?dropped.getY()+.15:s.pos().getY()+.5;
            double sourceZ=onGround?dropped.getZ():s.pos().getZ()+.5;
            if(inChest && !inInventory) {
                sourceX=(s.pos().getX()+s.chestPartner().getX())*.5+.5;
                sourceZ=(s.pos().getZ()+s.chestPartner().getZ())*.5+.5;
            }
            if(inInventory){sourceX=mc.player.getX();sourceY=mc.player.getEyeY()-.3;sourceZ=mc.player.getZ();}
            double dx=sourceX-mc.player.getX(),dy=sourceY-mc.player.getEyeY(),dz=sourceZ-mc.player.getZ();
            double distance=inInventory?0:Math.sqrt(dx*dx+dy*dy+dz*dz);
            if(!s.enabled() || s.track().isEmpty() || distance>32) { stop(e,!s.enabled()); continue; }
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
                // Start after initial position, gain and enclosure parameters are supplied below.
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
            {
                if(e.wasCarried!=inInventory){e.acousticJob=null;e.acousticTime=-1;e.extraPath=0;e.wasCarried=inInventory;e.energyJob=null;e.energyTime=-100;e.route=List.of();e.reflections=List.of();e.aperture=SpeakerPropagation.Aperture.EMPTY;}
                var acousticSource=inInventory?net.minecraft.core.BlockPos.containing(sourceX,sourceY,sourceZ):s.pos();
                var acousticPartner=inInventory?acousticSource:s.chestPartner();
                if(e.acousticTime<0)e.acoustic=SpeakerPropagation.Result.SILENT;
                var origin=new SpeakerPropagation.Point(sourceX,sourceY,sourceZ);
                var listener=new SpeakerPropagation.Point(mc.player.getX(),mc.player.getEyeY(),mc.player.getZ());
                if(e.acousticJob!=null && (!e.acousticJob.near(origin,listener,24*s.volume())
                        || !acousticSource.equals(e.acousticSource) || !acousticPartner.equals(e.acousticPartner)))e.acousticJob=null;
                e.energyOrigin=origin;e.energyListener=listener;e.energySource=acousticSource;e.energyPartner=acousticPartner;
                if(System.nanoTime()<acousticDeadline) {
                    // The audible result always uses today's listener position, never a finished old search.
                    var fresh=SpeakerAcoustics.begin(mc,sourceX,sourceY,sourceZ,acousticSource,acousticPartner,s.volume());
                    var current=fresh.refresh(e.route,e.aperture);
                    boolean changed=Math.abs(current.gain()-e.acoustic.gain())>.1 || Math.abs(current.cutoff()-e.acoustic.cutoff())>2000;
                    if(changed){e.reflections=List.of();e.energyJob=null;e.energyTime=-100;}
                    e.acoustic=new SpeakerPropagation.Result(current.distance(),current.gain(),current.cutoff(),current.arrival(),e.reflections);
                    e.extraPath=inInventory?0:Math.max(0,current.distance()-fresh.directDistance());
                    e.acousticTime=ticks;
                    if(!fresh.route().isEmpty())e.route=fresh.route();
                    if(e.acousticJob==null) {
                        e.acousticJob=SpeakerAcoustics.begin(mc,sourceX,sourceY,sourceZ,acousticSource,acousticPartner,s.volume());
                        e.acousticSource=acousticSource;e.acousticPartner=acousticPartner;
                    }

                }
            }
            if(!inInventory && e.acoustic.arrival()!=null) {
                var arrival=e.acoustic.arrival();
                double ax=arrival.x()-mc.player.getX(),az=arrival.z()-mc.player.getZ(),ah=Math.hypot(ax,az);
                if(ah>.05)pan=(-ax*Math.cos(yaw)-az*Math.sin(yaw))/ah;
            }
            var levels=SpeakerPcm.spatial(distance+e.extraPath,pan,s.volume(),24,
                    mc.options.getSoundSourceVolume(SoundSource.MASTER),mc.options.getSoundSourceVolume(SoundSource.RECORDS),inInventory);
            float openness=0;
            if(inChest && !inInventory) {
                var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)mc.level.getBlockEntity(s.pos());
                openness=chest.getOpenNess(1);
                if(mc.level.isLoaded(s.chestPartner()) && mc.level.getBlockEntity(s.chestPartner()) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity other)
                    openness=Math.max(openness,other.getOpenNess(1));
            }
            if(inShell)openness=((net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)mc.level.getBlockEntity(s.pos())).getProgress(1);
            if(inEnder && mc.level.getBlockEntity(s.pos()) instanceof net.minecraft.world.level.block.entity.EnderChestBlockEntity enderChest)
                openness=enderChest.getOpenNess(1);
            var water=SpeakerWaterWorld.measure(mc,new SpeakerPropagation.Point(sourceX,sourceY,sourceZ),
                    new SpeakerPropagation.Point(mc.player.getX(),mc.player.getEyeY(),mc.player.getZ()),
                    inInventory?List.of():e.route,!inInventory&&!onGround);
            e.voice.update(new SpeakerVoice.Control(levels,mc.isPaused(),inChest && !inInventory,
                    openness,!s.pos().equals(s.chestPartner()),inChest && mc.level.getBlockState(s.pos()).getBlock()
                    instanceof net.minecraft.world.level.block.CopperChestBlock,s.shell(),s.ender(),e.acoustic.gain(),e.acoustic.cutoff(),e.reflections,water));
            if(e.voice.status()==SpeakerVoice.Status.NEW)e.voice.start();
        }
        // Every voice receives its current control before any expensive background search.
        // Rotate this list independently: oldest direct update must not also monopolize background work.
        var background=new ArrayList<>(sources.values());
        if(!background.isEmpty())Collections.rotate(background,-(int)(ticks%background.size()));
        var tasks=new ArrayList<SpeakerWorkQueue.Task>();
        for(var e:background){
            if(e.voice==null || e.energyOrigin==null)continue;
            if(e.acousticJob!=null)tasks.add(deadline->{
                if(e.acousticJob.advance(256,deadline)){
                    if(!e.acousticJob.route().isEmpty())e.route=e.acousticJob.route();
                    if(!e.acousticJob.aperture().paths().isEmpty())e.aperture=e.acousticJob.aperture();
                    e.acousticJob=null;
                }
            });
            if(e.energyJob!=null && !e.energyJob.near(e.energyOrigin,e.energyListener))e.energyJob=null;
            if(e.energyJob!=null || ticks-e.energyTime>=10)tasks.add(deadline->{
                if(e.energyJob==null)
                    e.energyJob=SpeakerAcoustics.energy(mc,e.energyOrigin,e.energyListener,e.energySource,e.energyPartner);
                if(e.energyJob.advance(256,deadline)){
                    e.reflections=e.energyJob.response();e.energyTime=ticks;e.energyJob=null;
                } else if(ticks%2==0)e.reflections=e.energyJob.preview();
            });
        }
        SpeakerWorkQueue.service(tasks,totalDeadline,System::nanoTime);
    }
}
