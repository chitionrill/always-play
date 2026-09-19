package soke.musicdelay.jukebox;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Server-owned identity for one recording, independent of inventory slot. */
public final class RecordingReservation {
    private static final String KEY = "always_play_recording";
    public final String token;
    public final long created;
    private final ItemStack original;

    public RecordingReservation(ItemStack stack, String token, long created) {
        this.token = token; this.created = created; original = stack;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putString(KEY, token);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private boolean matches(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return !stack.isEmpty() && data != null && data.copyTag().getString(KEY).orElse("").equals(token);
    }

    public ItemStack find(ServerPlayer player) {
        ItemStack found = null;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (!matches(item)) continue;
            if (found != null || item.getCount() != 1 || !SharedJukeboxServer.clean(item)) return null;
            found = item;
        }
        // The cursor is still owned by this player while moving a disc between inventory slots.
        ItemStack cursor = player.containerMenu.getCarried();
        if (matches(cursor)) {
            if (found != null || cursor.getCount() != 1 || !SharedJukeboxServer.clean(cursor)) return null;
            found = cursor;
        }
        return found;
    }

    private void clear(ItemStack item) {
        if (!matches(item)) return;
        CompoundTag tag = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.remove(KEY);
        if (tag.isEmpty()) item.remove(DataComponents.CUSTOM_DATA);
        else item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public void clear(ServerPlayer player) {
        clear(original);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) clear(player.getInventory().getItem(slot));
        clear(player.containerMenu.getCarried());
        player.containerMenu.broadcastChanges();
    }
}
