package soke.musicdelay.speaker;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

public final class SpeakerItem extends BlockItem {
    public SpeakerItem(Block block, Properties properties) { super(block, properties); }

    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player.isSpectator()) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) SpeakerNetworking.openHand(serverPlayer, hand);
        return InteractionResult.SUCCESS;
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || player.isSpectator()) return InteractionResult.PASS;
        if (!player.isShiftKeyDown() && (context.getLevel().getBlockState(context.getClickedPos()).getBlock()
                instanceof net.minecraft.world.level.block.AbstractChestBlock<?>
                || context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock)) return InteractionResult.SUCCESS;
        if (!player.isShiftKeyDown()) return use(context.getLevel(), player, context.getHand());
        if (!context.getLevel().isClientSide()
                && SpeakerRegistry.ensureState(context.getItemInHand()).isEmpty()) return InteractionResult.FAIL;
        // Vanilla keeps the template in Creative; SpeakerBlock gives the placed copy a new identity.
        return super.useOn(context);
    }
}
