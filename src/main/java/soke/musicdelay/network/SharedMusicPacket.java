package soke.musicdelay.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Versioned, bounded wire format. No local filesystem paths cross the network. */
public record SharedMusicPacket(String action, String id, String value, String title,
                                String composer, BlockPos pos, long number, int total, byte[] data)
        implements CustomPacketPayload {
    public static final int CHUNK = 24 * 1024;
    public static final int MAX_FILE = 32 * 1024 * 1024;
    public static final Type<SharedMusicPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("music-delay-reducer", "shared_music_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SharedMusicPacket> CODEC = new StreamCodec<>() {
        @Override public SharedMusicPacket decode(RegistryFriendlyByteBuf b) {
            return new SharedMusicPacket(b.readUtf(24), b.readUtf(128), b.readUtf(512),
                    b.readUtf(64), b.readUtf(64), b.readBlockPos(), b.readLong(),
                    b.readVarInt(), b.readByteArray(CHUNK));
        }
        @Override public void encode(RegistryFriendlyByteBuf b, SharedMusicPacket p) {
            if (p.data.length > CHUNK) throw new IllegalArgumentException("Oversized music packet");
            b.writeUtf(p.action, 24); b.writeUtf(p.id, 128); b.writeUtf(p.value, 512);
            b.writeUtf(p.title, 64); b.writeUtf(p.composer, 64); b.writeBlockPos(p.pos);
            b.writeLong(p.number); b.writeVarInt(p.total); b.writeByteArray(p.data);
        }
    };
    public static SharedMusicPacket simple(String action, String id, String value) {
        return new SharedMusicPacket(action, id, value, "", "", BlockPos.ZERO, 0, 0, new byte[0]);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
