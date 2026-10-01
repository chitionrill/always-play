package soke.musicdelay.speaker;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;
import java.util.UUID;

/** Prototype save format. Reading never creates a replacement identity. */
public final class SpeakerData {
    private static final String ROOT = "always_play_speaker";
    private static final int VERSION = 1;

    private SpeakerData() { }

    public static CompoundTag encode(SpeakerState state) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", VERSION);
        tag.putString("id", state.id().toString());
        tag.putString("type", state.type().id());
        state.ownerId().ifPresent(id -> tag.putString("owner", id.toString()));
        tag.putBoolean("main", state.main());
        tag.putBoolean("enabled", state.enabled());
        tag.putFloat("volume", state.volume());
        tag.putFloat("effect", state.effectStrength());
        tag.putString("track", state.trackReference());
        tag.putLong("positionMillis", state.positionMillis());
        return tag;
    }

    public static Optional<SpeakerState> decode(CompoundTag tag) {
        if (tag.getInt("version").orElse(-1) != VERSION) return Optional.empty();
        try {
            Optional<UUID> owner = Optional.empty();
            if (tag.contains("owner")) owner = Optional.of(uuid(tag.getString("owner").orElseThrow()));
            return Optional.of(new SpeakerState(
                    uuid(tag.getString("id").orElseThrow()),
                    SpeakerType.byId(tag.getString("type").orElseThrow()).orElseThrow(),
                    owner,
                    tag.getBoolean("main").orElseThrow(),
                    tag.getBoolean("enabled").orElseThrow(),
                    tag.getFloat("volume").orElseThrow(),
                    tag.getFloat("effect").orElseThrow(),
                    tag.getString("track").orElseThrow(),
                    tag.getLong("positionMillis").orElseThrow()));
        } catch (IllegalArgumentException | java.util.NoSuchElementException e) {
            return Optional.empty();
        }
    }

    /** Preserve unrelated item metadata, including existing record data. */
    public static CompoundTag mergeInto(CompoundTag original, SpeakerState state) {
        CompoundTag result = original.copy();
        result.put(ROOT, encode(state));
        return result;
    }

    public static Optional<SpeakerState> read(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();
        return data.copyTag().getCompound(ROOT).flatMap(SpeakerData::decode);
    }

    /** The caller must check item type, permissions and server ownership. */
    public static void write(ItemStack stack, SpeakerState state) {
        CompoundTag original = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(mergeInto(original, state)));
    }

    private static UUID uuid(String text) {
        UUID result = UUID.fromString(text);
        if (!result.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException("Noncanonical UUID");
        return result;
    }
}
