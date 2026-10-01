package soke.musicdelay.speaker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Keep the complete item so unrelated components survive placement and pickup. */
public final class SpeakerBlockEntity extends BlockEntity {
    private ItemStack item = ItemStack.EMPTY;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(SpeakerRegistry.BLOCK_ENTITY, pos, state);
    }

    public ItemStack itemCopy() { return item.copy(); }

    public void advancePosition() {
        SpeakerData.read(item).filter(s -> s.enabled() && !s.trackReference().isEmpty()).ifPresent(s -> {
            SpeakerData.write(item, s.withPlayback(s.trackReference(), Math.min(86_400_000L, s.positionMillis() + 50)));
            setChanged();
        });
    }

    public void setItem(ItemStack value) {
        item = value.copyWithCount(1);
        setChanged();
    }

    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!item.isEmpty()) output.store("speakerItem", ItemStack.CODEC, item);
    }

    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        item = input.read("speakerItem", ItemStack.CODEC).orElse(ItemStack.EMPTY);
    }
}
