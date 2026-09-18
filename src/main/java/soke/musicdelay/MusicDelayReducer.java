package soke.musicdelay;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import soke.musicdelay.jukebox.CustomRecordData;
import soke.musicdelay.network.RecordCustomDataPayload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MusicDelayReducer implements ModInitializer {
	public static final String MOD_ID = "music-delay-reducer";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String RECORD_TRIGGER_NAME = "Clean";

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(RecordCustomDataPayload.TYPE, RecordCustomDataPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				soke.musicdelay.network.CustomTrackJukeboxStartPayload.TYPE,
				soke.musicdelay.network.CustomTrackJukeboxStartPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				soke.musicdelay.network.AmbientTrackJukeboxStartPayload.TYPE,
				soke.musicdelay.network.AmbientTrackJukeboxStartPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(RecordCustomDataPayload.TYPE, (payload, context) -> {
			context.server().execute(() -> {
				ServerPlayer player = context.player();
				ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
				if (stack.isEmpty()) return;

				// Повторная проверка на сервере (а не только на клиенте) — защита от рассинхрона,
				// если игрок успел сменить предмет в руке между открытием экрана и нажатием "Готово".
				if (!RECORD_TRIGGER_NAME.equals(stack.getHoverName().getString())) return;

				new CustomRecordData(payload.trackType(), payload.trackValue(), payload.title(), payload.composer())
						.writeTo(stack);
				stack.set(DataComponents.CUSTOM_NAME, Component.translatable("music-delay-reducer.record.default_name"));

				// Убираем родной jukebox_playable — иначе (а) в подсказке остаётся композитор
				// исходного ванильного диска вместо наших данных, (б) если наша будущая логика
				// воспроизведения (этап 4) ещё не перехватит вставку, ванильный трек не проиграется
				// сам собой поверх/вместо нашего.
				stack.remove(net.minecraft.core.component.DataComponents.JUKEBOX_PLAYABLE);

				java.util.List<Component> lore = new java.util.ArrayList<>();
				if (!payload.title().isBlank()) lore.add(Component.literal(payload.title()));
				if (!payload.composer().isBlank()) lore.add(Component.literal(payload.composer()));
				stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));

				player.containerMenu.broadcastChanges();
			});
		});
	}
}