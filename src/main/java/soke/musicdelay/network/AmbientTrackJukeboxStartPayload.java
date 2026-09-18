package soke.musicdelay.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// Пакет сервер -> клиенты рядом с блоком: "в проигрывателе на этой позиции заиграл обычный
// (не диск) ванильный трек с таким идентификатором звука". Отдельно от
// CustomTrackJukeboxStartPayload, потому что клиент по-разному резолвит и проигрывает эти два
// случая — тут через настоящий звуковой движок игры, там через собственный аудио-вывод.
public record AmbientTrackJukeboxStartPayload(BlockPos pos, String soundLocation) implements CustomPacketPayload {

    public static final Type<AmbientTrackJukeboxStartPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("music-delay-reducer", "ambient_track_jukebox_start"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AmbientTrackJukeboxStartPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, AmbientTrackJukeboxStartPayload::pos,
            ByteBufCodecs.STRING_UTF8, AmbientTrackJukeboxStartPayload::soundLocation,
            AmbientTrackJukeboxStartPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
