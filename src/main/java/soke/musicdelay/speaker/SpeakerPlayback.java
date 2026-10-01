package soke.musicdelay.speaker;

import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Local-file playback in the integrated server. Multiplayer playback is deferred. */
public final class SpeakerPlayback {
    private SpeakerPlayback() { }
    public record Snapshot(BlockPos pos, java.util.UUID id, String track, long offset, long epoch,
                           float volume, boolean enabled, boolean carried) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(Identifier.fromNamespaceAndPath("music-delay-reducer", "speaker_audio_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = new StreamCodec<>() {
            public Snapshot decode(RegistryFriendlyByteBuf b) { return new Snapshot(b.readBlockPos(), b.readUUID(), b.readUtf(512), b.readLong(), b.readLong(), b.readFloat(), b.readBoolean(), b.readBoolean()); }
            public void encode(RegistryFriendlyByteBuf b, Snapshot s) { b.writeBlockPos(s.pos); b.writeUUID(s.id); b.writeUtf(s.track,512); b.writeLong(s.offset); b.writeLong(s.epoch); b.writeFloat(s.volume); b.writeBoolean(s.enabled); b.writeBoolean(s.carried); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record End(BlockPos pos, java.util.UUID id, String track, long epoch) implements CustomPacketPayload {
        public static final Type<End> TYPE = new Type<>(Identifier.fromNamespaceAndPath("music-delay-reducer", "speaker_audio_end_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, End> CODEC = new StreamCodec<>() {
            public End decode(RegistryFriendlyByteBuf b) { return new End(b.readBlockPos(),b.readUUID(),b.readUtf(512),b.readLong()); }
            public void encode(RegistryFriendlyByteBuf b, End s) { b.writeBlockPos(s.pos); b.writeUUID(s.id); b.writeUtf(s.track,512); b.writeLong(s.epoch); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        SpeakerInventory.register();
        PayloadTypeRegistry.clientboundPlay().register(Snapshot.TYPE,Snapshot.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(End.TYPE,End.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(End.TYPE,(p,c)->c.server().execute(()->{
            var player=c.player(); var level=player.level();
            if (!c.server().isSingleplayer() || !c.server().isSingleplayerOwner(player.nameAndId())
                    || !level.isLoaded(p.pos) || player.distanceToSqr(p.pos.getX()+.5,p.pos.getY()+.5,p.pos.getZ()+.5)>4096) return;
            if (SpeakerInventory.finish(player,p)) return;
            if (level.getBlockEntity(p.pos) instanceof SpeakerBlockEntity be) {
                var item=be.itemCopy();
                SpeakerData.read(item).filter(s->s.id().equals(p.id) && s.trackReference().equals(p.track)
                        && s.enabled() && Math.abs(level.getGameTime()*50-s.positionMillis()-p.epoch)<=100).ifPresent(s->{
                    SpeakerData.write(item,s.withEnabled(false).withPlayback(s.trackReference(),0)); be.setItem(item);
                });
            }
        }));
    }
    public static void tick(Level level, BlockPos pos, BlockState block, SpeakerBlockEntity be) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSingleplayer()) return;
        // Snapshots are bounded by loaded ticking blocks and listener distance, not a scan of the world.
        if (level.getGameTime()%5==0) SpeakerData.read(be.itemCopy()).ifPresent(s->{
            var packet=new Snapshot(pos,s.id(),s.trackReference(),s.positionMillis(),
                    level.getGameTime()*50-s.positionMillis(),s.volume(),s.enabled(),false);
            for (var player:server.players()) if (server.getServer().isSingleplayerOwner(player.nameAndId())
                    && player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)<4096
                    && ServerPlayNetworking.canSend(player,Snapshot.TYPE)) { ServerPlayNetworking.send(player,packet); SpeakerNetworking.refreshBlock(player,pos,s); }
        });
        be.advancePosition();
    }
}
