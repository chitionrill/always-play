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
    private static long ticks,diagnosticAt,previousTick;
    private static Object world;
    private static final int MAX_VOICES=8;
    private static final class Emitter {
        SpeakerPlayback.Snapshot state;
        long seen;
        final SpeakerSourceLease lease=new SpeakerSourceLease();
        long quietSince=-1;
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
        long reflectionGeneration,reflectionTime=-1;
        String acousticDiagnostic="not-sampled";
        List<SpeakerPropagation.Point> route=List.of();
        SpeakerPropagation.Aperture aperture=SpeakerPropagation.Aperture.EMPTY;
        List<SpeakerPropagation.Reflection> reflections=List.of();
        SpeakerPropagation.Job acousticJob;
        net.minecraft.core.BlockPos acousticSource,acousticPartner;
        Emitter(SpeakerPlayback.Snapshot s) { state=s; }
    }
    private static double reflectionPower(List<SpeakerPropagation.Reflection> response){
        double sum=0;for(var tap:response)sum+=tap.gain()*tap.gain();return sum;
    }
    private SpeakerWorldAudio() { }
    public static void receive(SpeakerPlayback.Snapshot packet) {
        var mc=Minecraft.getInstance();
        if (!mc.hasSingleplayerServer() || mc.level==null) return;
        if (world!=mc.level) { clear(); world=mc.level; }
        var e=sources.computeIfAbsent(packet.id(),id->new Emitter(packet));
        if(SpeakerDiagnostics.recording() && (e.state.enabled()!=packet.enabled() || !e.state.track().equals(packet.track())
                || e.state.carried()!=packet.carried() || !e.state.pos().equals(packet.pos()) || e.state.itemEntityId()!=packet.itemEntityId()))
            SpeakerDiagnostics.event("snapshot-change",packet.id()+" enabled="+packet.enabled()+" offset="+packet.offset()+" carried="+packet.carried());
        if (SpeakerContinuity.restart(e.state.track(),packet.track(),e.state.offset(),packet.offset(),packet.enabled())) {
            if(e.voice!=null)soke.musicdelay.MusicDelayReducer.LOGGER.info("[AlwaysPlay audio] restart {}: trackChanged={}, enabled={}, position={} -> {}, epochDelta={}",packet.id(),!e.state.track().equals(packet.track()),packet.enabled(),e.state.offset(),packet.offset(),packet.epoch()-e.state.epoch());
            stop(e,!packet.enabled(),packet.enabled()?"track-or-position-change":"disabled-packet"); e.ended=false;
        }
        e.state=packet; e.seen=ticks;e.lease.refresh(ticks);
    }
    private static void confirmSource(Minecraft mc,Emitter e){
        var server=mc.getSingleplayerServer();if(server==null)return;
        long token=e.lease.begin();if(token<0)return;
        var clientWorld=mc.level;var dimension=clientWorld.dimension();var playerId=mc.player.getUUID();var requested=e.state;
        if(SpeakerDiagnostics.recording())SpeakerDiagnostics.event("source-check",requested.id()+" clientAge="+(ticks-e.seen));
        server.execute(()->{
            soke.musicdelay.speaker.SpeakerSourceCheck.Result result;
            try{result=soke.musicdelay.speaker.SpeakerSourceCheck.inspect(server.getLevel(dimension),server.getPlayerList().getPlayer(playerId),requested);}
            catch(Exception error){
                mc.execute(()->{
                    if(mc.level!=clientWorld||world!=clientWorld||sources.get(requested.id())!=e||!e.lease.finish(token))return;
                    e.lease.refresh(ticks); // Failed verification is not evidence of deletion. Retry later.
                    if(SpeakerDiagnostics.recording())SpeakerDiagnostics.event("source-check-error",requested.id()+" type="+error.getClass().getSimpleName());
                });return;
            }
            var answer=result;
            mc.execute(()->{
                if(mc.level!=clientWorld||world!=clientWorld||sources.get(requested.id())!=e||!e.lease.finish(token))return;
                if(SpeakerDiagnostics.recording())SpeakerDiagnostics.event("source-check-result",requested.id()+" reason="+answer.reason());
                if(answer.snapshot()==null){stop(e,false,"server-"+answer.reason());sources.remove(requested.id());}
                else receive(answer.snapshot());
            });
        });
    }
    private static boolean terminal(SpeakerVoice v) {
        return v.status()==SpeakerVoice.Status.STOPPED || v.status()==SpeakerVoice.Status.FINISHED || v.status()==SpeakerVoice.Status.FAILED;
    }
    private static void stop(Emitter e){stop(e,false,"world-clear");}
    private static void stop(Emitter e,boolean tail,String reason) {
        if(e.voice!=null && SpeakerDiagnostics.recording())SpeakerDiagnostics.event("voice-stop",e.state.id()+" reason="+reason+" tail="+tail+" seenAge="+(ticks-e.seen)+" status="+e.voice.status());
        e.energyJob=null;e.energyTime=-100;e.reflectionTime=-1;e.reflections=List.of();
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
        long diagnosticStart=System.nanoTime();
        boolean diagnosticSample=SpeakerDiagnostics.recording() && diagnosticStart-diagnosticAt>=500_000_000L;
        if(diagnosticSample){
            diagnosticAt=diagnosticStart;
            SpeakerDiagnostics.runtimeSample();
            SpeakerDiagnostics.event("world","present="+(mc.level!=null)+" paused="+mc.isPaused()+" tickGapNs="+(previousTick==0?0:diagnosticStart-previousTick)+" sources="+sources.size()+" heapUsed="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()));
            if(mc.player!=null)SpeakerDiagnostics.event("listener","x="+mc.player.getX()+" y="+mc.player.getEyeY()+" z="+mc.player.getZ()+" yaw="+mc.player.getYRot()+" dimension="+mc.level.dimension());
        }
        previousTick=diagnosticStart;
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
            boolean present=inInventory||blockPresent||onGround||inChest||inShell||inEnder;
            // Missing geometry/item has a short handoff grace; a missing heartbeat requires authority.
            if(e.lease.missing(ticks,present)){
                stop(e,false,"client-location-missing");sources.remove(s.id());continue;
            }
            if(e.lease.stale(ticks) && present && s.enabled())confirmSource(mc,e);
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
            if(!s.enabled() || s.track().isEmpty()) { stop(e,!s.enabled(),s.enabled()?"empty-track":"disabled-state"); continue; }
            double strength=s.volume()*mc.options.getSoundSourceVolume(SoundSource.MASTER)*mc.options.getSoundSourceVolume(SoundSource.RECORDS);
            // A conservative bound: do not discard a reflected route merely because the direct path is blocked.
            if(!SpeakerDistance.audible(distance,strength,e.voice!=null)){
                if(e.quietSince<0)e.quietSince=ticks;
                if(e.voice==null)continue;
                if(ticks-e.quietSince>=40){
                    soke.musicdelay.MusicDelayReducer.LOGGER.info("[AlwaysPlay audio] virtualize {}: estimated level={}, offset={}ms",s.id(),SpeakerDistance.gain(distance,strength),s.offset());
                    stop(e,false,"inaudible");continue;
                }
            }else e.quietSince=-1;
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
                soke.musicdelay.MusicDelayReducer.LOGGER.info("[AlwaysPlay audio] start {} at {}ms, distance={}",s.id(),offset,distance);
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
                stop(e,false,status==SpeakerVoice.Status.FAILED?"audio-failed":"track-finished"); continue;
            }
            double yaw=Math.toRadians(mc.player.getYRot()),horizontal=Math.hypot(dx,dz);
            double pan=horizontal<.001?0:(-dx*Math.cos(yaw)-dz*Math.sin(yaw))/horizontal;
            {
                if(e.wasCarried!=inInventory){e.acousticJob=null;e.acousticTime=-1;e.extraPath=0;e.wasCarried=inInventory;e.energyJob=null;e.energyTime=-100;e.reflectionTime=-1;e.route=List.of();e.reflections=List.of();e.aperture=SpeakerPropagation.Aperture.EMPTY;}
                var acousticSource=inInventory?net.minecraft.core.BlockPos.containing(sourceX,sourceY,sourceZ):s.pos();
                var acousticPartner=inInventory?acousticSource:s.chestPartner();
                if(e.acousticTime<0)e.acoustic=SpeakerPropagation.Result.SILENT;
                var origin=new SpeakerPropagation.Point(sourceX,sourceY,sourceZ);
                var listener=new SpeakerPropagation.Point(mc.player.getX(),mc.player.getEyeY(),mc.player.getZ());
                if(e.acousticJob!=null && (!e.acousticJob.near(origin,listener,s.volume())
                        || !acousticSource.equals(e.acousticSource) || !acousticPartner.equals(e.acousticPartner)))e.acousticJob=null;
                e.energyOrigin=origin;e.energyListener=listener;e.energySource=acousticSource;e.energyPartner=acousticPartner;
                if(System.nanoTime()<acousticDeadline) {
                    // The audible result always uses today's listener position, never a finished old search.
                    var fresh=SpeakerAcoustics.begin(mc,sourceX,sourceY,sourceZ,acousticSource,acousticPartner,s.volume());
                    var current=fresh.refresh(e.route,e.aperture);
                    boolean changed=Math.abs(current.gain()-e.acoustic.gain())>.1 || Math.abs(current.cutoff()-e.acoustic.cutoff())>2000;
                    // A changed direct path requests a new response, but is not proof that
                    // all reflected paths disappeared. Keep the last complete response.
                    if(changed)e.energyTime=-100;
                    if(diagnosticSample)e.acousticDiagnostic=fresh.diagnostics();
                    e.acoustic=new SpeakerPropagation.Result(current.distance(),current.gain(),current.cutoff(),current.arrival(),e.reflections,current.spectrum());
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
            var levels=SpeakerPcm.spatial(distance+e.extraPath,pan,s.volume(),
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
            var spatialReflections=new ArrayList<SpeakerPropagation.Reflection>();
            for(var reflection:e.reflections){
                float reflectionPan=0;
                if(reflection.arrival()!=null){
                    double rx=reflection.arrival().x()-mc.player.getX(),rz=reflection.arrival().z()-mc.player.getZ(),rh=Math.hypot(rx,rz);
                    if(rh>.05)reflectionPan=(float)Math.clamp((-rx*Math.cos(yaw)-rz*Math.sin(yaw))/rh,-1,1);
                }
                spatialReflections.add(reflection.withPan(reflectionPan));
            }
            e.voice.update(new SpeakerVoice.Control(levels,mc.isPaused(),inChest && !inInventory,
                    openness,!s.pos().equals(s.chestPartner()),inChest && mc.level.getBlockState(s.pos()).getBlock()
                    instanceof net.minecraft.world.level.block.CopperChestBlock,s.shell(),s.ender(),e.acoustic.gain(),e.acoustic.cutoff(),spatialReflections,water,e.acoustic.spectrum()));
            if(diagnosticSample)SpeakerDiagnostics.event("source",s.id()+" x="+sourceX+" y="+sourceY+" z="+sourceZ+" distance="+distance+" extraPath="+e.extraPath+" gain="+levels.gain()+" transmission="+e.acoustic.gain()+" cutoff="+e.acoustic.cutoff()+" pan="+levels.pan()+" reflections="+e.reflections.size()+" reflectionGeneration="+e.reflectionGeneration+" reflectionAgeTicks="+(e.reflectionTime<0?-1:ticks-e.reflectionTime)+" reflectionPower="+reflectionPower(e.reflections)+" "+e.acousticDiagnostic+" confirmationPending="+e.lease.pending()+" snapshotAge="+(ticks-e.seen)+" carried="+inInventory+" chest="+inChest+" shell="+inShell+" ender="+inEnder+" openness="+openness+" water="+water+" status="+e.voice.status());
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
                    e.reflections=e.energyJob.response();e.energyTime=ticks;e.reflectionTime=ticks;e.energyJob=null;e.reflectionGeneration++;
                    if(SpeakerDiagnostics.recording())SpeakerDiagnostics.event("reflection-publish",e.state.id()+" generation="+e.reflectionGeneration+" taps="+e.reflections.size()+" power="+reflectionPower(e.reflections));
                }
                // Partial responses contain an arbitrary subset of paths depending on
                // work slicing. Never replace audible acoustics with that subset.
            });
        }
        SpeakerWorkQueue.service(tasks,totalDeadline,System::nanoTime);
        if(diagnosticSample)SpeakerDiagnostics.event("acoustic-work","ns="+(System.nanoTime()-diagnosticStart)+" tasks="+tasks.size());
    }
}
