package soke.musicdelay.speaker;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Small prototype menu protocol: clients request actions, never supply saved state. */
public final class SpeakerNetworking {
    public record Menu(UUID token, UUID speakerId, boolean enabled, boolean main, boolean canConfigure, boolean opening, String track, boolean placed)
            implements CustomPacketPayload {
        public static final Type<Menu> TYPE = new Type<>(Identifier.fromNamespaceAndPath("music-delay-reducer", "speaker_menu_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Menu> CODEC = new StreamCodec<>() {
            public Menu decode(RegistryFriendlyByteBuf b) {
                return new Menu(b.readUUID(), b.readUUID(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readUtf(512), b.readBoolean());
            }
            public void encode(RegistryFriendlyByteBuf b, Menu p) {
                b.writeUUID(p.token); b.writeUUID(p.speakerId); b.writeBoolean(p.enabled);
                b.writeBoolean(p.main); b.writeBoolean(p.canConfigure); b.writeBoolean(p.opening); b.writeUtf(p.track, 512); b.writeBoolean(p.placed);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Action(UUID token, int action, String track) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Identifier.fromNamespaceAndPath("music-delay-reducer", "speaker_action_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = new StreamCodec<>() {
            public Action decode(RegistryFriendlyByteBuf b) { return new Action(b.readUUID(), b.readUnsignedByte(), b.readUtf(80)); }
            public void encode(RegistryFriendlyByteBuf b, Action p) { b.writeUUID(p.token); b.writeByte(p.action); b.writeUtf(p.track, 80); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record Target(UUID token, UUID speakerId, ResourceKey<Level> dimension,
                          BlockPos pos, InteractionHand hand, long openedAt) { }
    private static final Map<UUID, Target> openMenus = new HashMap<>();

    private SpeakerNetworking() { }

    public static void register() {
        SpeakerPlayback.register();
        PayloadTypeRegistry.clientboundPlay().register(Menu.TYPE, Menu.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Action.TYPE, Action.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Action.TYPE, (packet, context) ->
                context.server().execute(() -> act(context.player(), packet)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> openMenus.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> openMenus.clear());
    }

    public static void openHand(ServerPlayer player, InteractionHand hand) {
        if (player.isSpectator()) return;
        Optional<SpeakerState> found = SpeakerRegistry.ensureState(player.getItemInHand(hand));
        if (found.isEmpty()) {
            player.sendOverlayMessage(Component.literal("Не удалось прочитать данные колонки."));
            return;
        }
        open(player, found.get(), null, hand);
    }

    public static void openBlock(ServerPlayer player, BlockPos pos) {
        if (player.isSpectator() || !reachable(player, pos)) return;
        if (player.level().getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
            SpeakerData.read(speaker.itemCopy()).ifPresent(state -> open(player, state, pos.immutable(), null));
        }
    }

    private static void open(ServerPlayer player, SpeakerState state, BlockPos pos, InteractionHand hand) {
        if (!ServerPlayNetworking.canSend(player, Menu.TYPE)) return;
        Target target = new Target(UUID.randomUUID(), state.id(), player.level().dimension(),
                pos, hand, player.level().getGameTime());
        openMenus.put(player.getUUID(), target);
        send(player, target, state, true);
    }

    private static boolean reachable(ServerPlayer player, BlockPos pos) {
        return player.level().isLoaded(pos) && player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= 36
                && player.mayInteract(player.level(), pos);
    }

    private static void act(ServerPlayer player, Action action) {
        Target target = openMenus.get(player.getUUID());
        if (target == null || !target.token.equals(action.token) || player.isSpectator()
                || !player.isAlive() || !target.dimension.equals(player.level().dimension())) return;
        if (player.level().getGameTime() - target.openedAt > 1200) {
            openMenus.remove(player.getUUID());
            player.sendOverlayMessage(Component.literal("Открой меню колонки заново."));
            return;
        }
        SpeakerBlockEntity blockEntity = null;
        ItemStack item;
        if (target.pos != null) {
            if (!reachable(player, target.pos) || !(player.level().getBlockEntity(target.pos) instanceof SpeakerBlockEntity speaker)) return;
            blockEntity = speaker;
            item = speaker.itemCopy();
        } else item = player.getItemInHand(target.hand);
        if (!item.is(SpeakerRegistry.ITEM)) return;
        Optional<SpeakerState> found = SpeakerData.read(item);
        if (found.isEmpty() || !found.get().id().equals(target.speakerId)) return;
        SpeakerState state = found.get();
        boolean canConfigure = state.ownerId().isEmpty() || state.ownerId().get().equals(player.getUUID());
        SpeakerState updated;
        if (action.action == 0) updated = state.withEnabled(!state.enabled());
        else if (action.action == 1 && canConfigure) updated = state.withMain(!state.main());
        else if (action.action == 2 && canConfigure && player.level().getServer().isSingleplayer()
                && player.level().getServer().isSingleplayerOwner(player.nameAndId())
                && action.track.matches("local:[0-9a-f]{64}")) {
            updated = state.withPlayback(action.track, 0).withEnabled(true);
        } else return;
        SpeakerData.write(item, updated);
        if (blockEntity != null) blockEntity.setItem(item);
        else {
            if (updated.enabled() && (action.action == 2 || !state.enabled())) SpeakerInventory.promote(player,item);
            player.inventoryMenu.broadcastChanges();
        }
        send(player, target, updated, false);
    }

    public static void refreshBlock(ServerPlayer player, BlockPos pos, SpeakerState state) {
        Target target = openMenus.get(player.getUUID());
        if (target != null && pos.equals(target.pos) && state.id().equals(target.speakerId)
                && target.dimension.equals(player.level().dimension()) && reachable(player, pos)) {
            send(player, target, state, false);
        }
    }

    public static void refreshHand(ServerPlayer player, SpeakerState state) {
        Target target = openMenus.get(player.getUUID());
        if (target != null && target.pos == null && state.id().equals(target.speakerId)
                && target.dimension.equals(player.level().dimension())
                && SpeakerData.read(player.getItemInHand(target.hand)).map(s->s.id().equals(state.id())).orElse(false)) {
            send(player,target,state,false);
        }
    }

    private static void send(ServerPlayer player, Target target, SpeakerState state, boolean opening) {
        boolean canConfigure = state.ownerId().isEmpty() || state.ownerId().get().equals(player.getUUID());
        ServerPlayNetworking.send(player, new Menu(target.token, state.id(), state.enabled(), state.main(), canConfigure, opening, state.trackReference(), target.pos != null));
    }
}
