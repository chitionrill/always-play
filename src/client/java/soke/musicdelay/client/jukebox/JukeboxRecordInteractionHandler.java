package soke.musicdelay.client.jukebox;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import soke.musicdelay.client.gui.RecordTrackChooserScreen;
import soke.musicdelay.jukebox.CustomRecordData;

// Перехватывает взаимодействие игрока с блоком "Проигрыватель пластинок" (Jukebox) в двух случаях:
//
// 1) В руке предмет, переименованный на наковальне в "Clean" — триггер "хочу записать/
//    перезаписать кастомную пластинку". Открываем (только на клиенте) экран выбора трека.
//
// 2) В руке уже готовая кастомная пластинка (есть CustomRecordData) и проигрыватель пуст —
//    вставляем её сами. Это нужно делать вручную, потому что при записи мы намеренно удалили у
//    предмета ванильный компонент JUKEBOX_PLAYABLE (см. MusicDelayReducer.java) — без него
//    ванильная логика вставки этот предмет просто не замечает.
//
// Вытаскивание (шифт+ПКМ по занятому проигрывателю) и выпадение при поломке блока НЕ трогаем —
// эта логика в игре завязана на сам блок и общий контейнер-слот (ContainerSingleItem), а не на
// компонент предмета, так что должна работать как обычно даже для нашего кастомного предмета.
//
// После физической вставки (блок-состояние HAS_RECORD + предмет в блок-энтити) запускает
// воспроизведение в зависимости от типа записанного трека: настоящий диск через штатный
// JukeboxSongPlayer, обычный ванильный трек и свой файл — каждый своим сетевым пакетом клиентам
// рядом с блоком.
public class JukeboxRecordInteractionHandler {

    private static final String TRIGGER_NAME = "Clean";

    public static void register() {
        UseBlockCallback.EVENT.register(JukeboxRecordInteractionHandler::onUseBlock);
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        BlockPos pos = hitResult.getBlockPos();
        BlockState state = world.getBlockState(pos);
        if (!state.is(Blocks.JUKEBOX)) return InteractionResult.PASS;

        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) return InteractionResult.PASS;

        if (stack.has(DataComponents.CUSTOM_NAME) && TRIGGER_NAME.equals(stack.getHoverName().getString())) {
            if (world.isClientSide()) {
                Minecraft.getInstance().gui.setScreen(new RecordTrackChooserScreen(null));
            }
            return InteractionResult.SUCCESS;
        }

        CustomRecordData recordData = CustomRecordData.read(stack);
        if (recordData != null && state.hasProperty(JukeboxBlock.HAS_RECORD) && !state.getValue(JukeboxBlock.HAS_RECORD)) {
            if (!world.isClientSide()) {
                insertCustomRecord(world, pos, player, hand, stack, recordData);
            }
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    private static void insertCustomRecord(Level world, BlockPos pos, Player player, InteractionHand hand,
                                           ItemStack heldStack, CustomRecordData recordData) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof JukeboxBlockEntity jukebox)) return;

        ItemStack toInsert = heldStack.copyWithCount(1);
        jukebox.setTheItem(toInsert);

        BlockState state = world.getBlockState(pos);
        world.setBlock(pos, state.setValue(JukeboxBlock.HAS_RECORD, true), 3);

        heldStack.shrink(1);
        player.setItemInHand(hand, heldStack);
        player.containerMenu.broadcastChanges();

        if ("VANILLA_DISC".equals(recordData.trackType())) {
            net.minecraft.resources.Identifier songId = net.minecraft.resources.Identifier.parse(recordData.trackValue());
            net.minecraft.core.Holder<net.minecraft.world.item.JukeboxSong> song =
                    soke.musicdelay.jukebox.VanillaJukeboxSongResolver.findById(world, songId);
            if (song != null) {
                jukebox.getSongPlayer().play(world, song);
            }
        } else if ("CUSTOM".equals(recordData.trackType()) && world instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            var payload = new soke.musicdelay.network.CustomTrackJukeboxStartPayload(pos, recordData.trackValue());
            for (net.minecraft.server.level.ServerPlayer tracking :
                    net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(serverLevel, pos)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(tracking, payload);
            }
        } else if ("VANILLA".equals(recordData.trackType()) && world instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            var payload = new soke.musicdelay.network.AmbientTrackJukeboxStartPayload(pos, recordData.trackValue());
            for (net.minecraft.server.level.ServerPlayer tracking :
                    net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(serverLevel, pos)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(tracking, payload);
            }
        }
    }
}