package soke.musicdelay.speaker;

import java.util.*;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/** One vanilla shulker layer only. Container components are immutable: always commit modified copies. */
public final class SpeakerStorage {
    private SpeakerStorage() { }
    public static boolean box(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock;
    }
    public static boolean relevant(ItemStack stack) { return stack.is(SpeakerRegistry.ITEM) || box(stack); }
    public static boolean contains(ItemStack root,UUID id) {
        if(root.is(SpeakerRegistry.ITEM))return SpeakerData.read(root).map(s->s.id().equals(id)).orElse(false);
        if(!box(root))return false;
        return root.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).nonEmptyItemCopyStream()
                .anyMatch(item->item.is(SpeakerRegistry.ITEM) && SpeakerData.read(item).map(s->s.id().equals(id)).orElse(false));
    }
    public static final class View {
        public final List<ItemStack> items=new ArrayList<>();
        public final Set<ItemStack> nested=Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<ItemStack,NonNullList<ItemStack>> boxes=new IdentityHashMap<>();
        public View(List<ItemStack> roots) {
            for(var root:roots) {
                if(root.is(SpeakerRegistry.ITEM))items.add(root);
                else if(box(root)) {
                    var content=NonNullList.withSize(27,ItemStack.EMPTY);
                    root.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).copyInto(content);
                    boxes.put(root,content);
                    for(var item:content)if(item.is(SpeakerRegistry.ITEM)) { items.add(item);nested.add(item); }
                }
            }
        }
        public void commit() {
            boxes.forEach((root,items)->{
                var changed=ItemContainerContents.fromItems(items);
                if(!changed.equals(root.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY)))root.set(DataComponents.CONTAINER,changed);
            });
        }
    }
}
