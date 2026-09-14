package soke.musicdelay.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// Пакет клиент -> сервер: "запиши на пластинку, которую я держу в основной руке, вот такой трек
// и метаданные". Всегда про основную руку — обработчик взаимодействия с проигрывателем реагирует
// только на неё (см. JukeboxRecordInteractionHandler), так что второе поле не нужно.
//
// trackType/trackValue — тот же формат, что Playlist.PlaylistEntry ("VANILLA"/"CUSTOM" + значение).
public record RecordCustomDataPayload(String trackType, String trackValue, String title, String composer)
        implements CustomPacketPayload {

    public static final Type<RecordCustomDataPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("music-delay-reducer", "record_custom_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecordCustomDataPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RecordCustomDataPayload::trackType,
            ByteBufCodecs.STRING_UTF8, RecordCustomDataPayload::trackValue,
            ByteBufCodecs.STRING_UTF8, RecordCustomDataPayload::title,
            ByteBufCodecs.STRING_UTF8, RecordCustomDataPayload::composer,
            RecordCustomDataPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
