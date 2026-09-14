package soke.musicdelay.jukebox;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

// Данные кастомной записи, привязанной к пластинке: ссылка на трек (type/value — тот же формат,
// что у Playlist.PlaylistEntry: "VANILLA"+Identifier звука, либо "CUSTOM"+путь к файлу) и
// метаданные, которые игрок ввёл сам (title/composer).
//
// Специально в common-модуле (src/main/java), а не в client — эти данные должен уметь
// записывать и обработчик сетевого пакета на "сервере" (в одиночной игре это тот же процесс,
// но отдельный логический сервер), а он не имеет доступа к client-классам.
//
// Хранится на предмете через ванильный DataComponents.CUSTOM_DATA под своим namespaced-ключом
// "always_play", чтобы не пересекаться с данными других модов/самой игры.
public record CustomRecordData(String trackType, String trackValue, String title, String composer) {

    private static final String ROOT_KEY = "always_play";
    private static final String TYPE_KEY = "trackType";
    private static final String VALUE_KEY = "trackValue";
    private static final String TITLE_KEY = "title";
    private static final String COMPOSER_KEY = "composer";

    public static boolean isPresent(ItemStack stack) {
        return read(stack) != null;
    }

    public static CustomRecordData read(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag root = data.copyTag();
        CompoundTag tag = root.getCompound(ROOT_KEY).orElse(null);
        if (tag == null) return null;
        String type = tag.getString(TYPE_KEY).orElse("");
        String value = tag.getString(VALUE_KEY).orElse("");
        if (type.isEmpty() || value.isEmpty()) return null;
        return new CustomRecordData(type, value, tag.getString(TITLE_KEY).orElse(""), tag.getString(COMPOSER_KEY).orElse(""));
    }

    public void writeTo(ItemStack stack) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag tag = new CompoundTag();
        tag.putString(TYPE_KEY, trackType);
        tag.putString(VALUE_KEY, trackValue);
        tag.putString(TITLE_KEY, title == null ? "" : title);
        tag.putString(COMPOSER_KEY, composer == null ? "" : composer);
        root.put(ROOT_KEY, tag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }
}
