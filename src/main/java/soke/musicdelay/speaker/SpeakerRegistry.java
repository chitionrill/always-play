package soke.musicdelay.speaker;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import java.util.Optional;

public final class SpeakerRegistry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("music-delay-reducer", "small_speaker");
    public static final SpeakerBlock BLOCK = new SpeakerBlock(BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ID)).strength(1.5f).sound(SoundType.WOOD)
            .noOcclusion().pushReaction(PushReaction.IMMOVEABLE));
    public static final SpeakerItem ITEM = new SpeakerItem(BLOCK, new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix().stacksTo(1)
            .component(DataComponents.ITEM_NAME, Component.literal("Деревянная колонка")));
    public static final BlockEntityType<SpeakerBlockEntity> BLOCK_ENTITY =
            FabricBlockEntityTypeBuilder.create(SpeakerBlockEntity::new, BLOCK).build();

    private SpeakerRegistry() { }

    public static Optional<SpeakerState> ensureState(ItemStack stack) {
        if (!stack.is(ITEM) || stack.getCount() != 1) return Optional.empty();
        Optional<SpeakerState> existing = SpeakerData.read(stack);
        if (existing.isPresent()) return existing;
        CustomData custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (custom.copyTag().contains("always_play_speaker")) return Optional.empty();
        SpeakerState created = SpeakerState.create(SpeakerType.SMALL);
        SpeakerData.write(stack, created);
        return Optional.of(created);
    }

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK, ID, BLOCK);
        Registry.register(BuiltInRegistries.ITEM, ID, ITEM);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ID, BLOCK_ENTITY);
        SpeakerNetworking.register();

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player.isSpectator()) return InteractionResult.PASS;
            if (level.getBlockState(hit.getBlockPos()).is(BLOCK)) {
                // Sneaking with a speaker places another beside it; normal use opens this speaker.
                if (player.isShiftKeyDown() && player.getItemInHand(hand).is(ITEM)) return InteractionResult.PASS;
                if (player instanceof ServerPlayer serverPlayer) SpeakerNetworking.openBlock(serverPlayer, hit.getBlockPos());
                return InteractionResult.SUCCESS;
            }
            // Open the held speaker even when looking at a chest or another interactive block.
            if (!player.isShiftKeyDown() && player.getItemInHand(hand).is(ITEM)) {
                if (player instanceof ServerPlayer serverPlayer) SpeakerNetworking.openHand(serverPlayer, hand);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!level.getBlockState(pos).is(BLOCK)) return InteractionResult.PASS;
            if (player.isSpectator() || !player.mayBuild()) return InteractionResult.FAIL;
            if (level.isClientSide()) return InteractionResult.SUCCESS;
            if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.mayInteract(serverPlayer.level(), pos)) {
                return InteractionResult.FAIL;
            }
            if (!(level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker)) return InteractionResult.FAIL;
            ItemStack item = speaker.itemCopy();
            if (item.isEmpty()) return InteractionResult.FAIL;
            // Refuse instead of losing or duplicating the speaker when every slot is occupied.
            if (player.getInventory().getFreeSlot() < 0) {
                player.sendOverlayMessage(Component.literal("Освободи одно место в инвентаре для колонки."));
                return InteractionResult.FAIL;
            }
            if (level.removeBlock(pos, false)) player.getInventory().add(item);
            return InteractionResult.SUCCESS;
        });
    }
}
