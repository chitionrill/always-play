package soke.musicdelay.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// Пакет сервер -> клиенты рядом с блоком: "в проигрывателе на этой позиции заиграл кастомный
// файл-трек по такому-то пути". Нужен только для CUSTOM-треков — настоящие диски (VANILLA_DISC)
// уже сами рассылают звук через штатный JukeboxSongPlayer.play(...), а для своего файла у игры
// в принципе нет для этого механизма, потому что она о таких файлах ничего не знает.
public record CustomTrackJukeboxStartPayload(BlockPos pos, String trackValue) implements CustomPacketPayload {

    public static final Type<CustomTrackJukeboxStartPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("music-delay-reducer", "custom_track_jukebox_start"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CustomTrackJukeboxStartPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CustomTrackJukeboxStartPayload::pos,
            ByteBufCodecs.STRING_UTF8, CustomTrackJukeboxStartPayload::trackValue,
            CustomTrackJukeboxStartPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
