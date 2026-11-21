package dev.muon.irons_apothic.category;

import dev.muon.irons_apothic.util.Debug;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Apotheosis 7.4.8 only ships weapon/armor/tool loot categories, so a ring or an amulet rolled from a
 * loot pool is {@code LootCategory.NONE} and never receives affixes. This registers two extra
 * categories that are driven by the {@code curios} item tags used by Iron's Spells:
 *
 * <ul>
 *     <li>{@code curios:ring} -&gt; {@code irons_apothic:ring} ("ring")</li>
 *     <li>{@code curios:necklace} -&gt; {@code irons_apothic:necklace} ("necklace")</li>
 * </ul>
 *
 * <p>{@link LootCategory#register(LootCategory, String, java.util.function.Predicate, EquipmentSlot[])}
 * is public in 7.4.8 and {@code LootCategory.BY_ID} / {@code LootCategory.VALUES} are unmodifiable
 * <em>views</em> over the internal mutable collections, so categories registered here immediately show
 * up in {@code LootCategory#byId}, in {@code LootCategory.CODEC} (used by every affix's {@code types}
 * field) and in {@code LootCategory#forItem}.</p>
 */
public final class CurioLootCategories {

    public static final TagKey<Item> RING_TAG = TagKey.create(Registries.ITEM, new ResourceLocation("curios", "ring"));
    public static final TagKey<Item> NECKLACE_TAG = TagKey.create(Registries.ITEM, new ResourceLocation("curios", "necklace"));

    private static boolean registered;
    private static LootCategory ring;
    private static LootCategory necklace;

    private CurioLootCategories() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        // A single (dummy) equipment slot keeps every Apotheosis code path that does getSlots()[0] happy;
        // affix attribute application for curios is handled by CurioAffixAttributeBridge instead.
        EquipmentSlot[] slots = new EquipmentSlot[]{EquipmentSlot.MAINHAND};
        ring = LootCategory.register(LootCategory.NONE, "ring", CurioLootCategories::isRing, slots);
        necklace = LootCategory.register(LootCategory.NONE, "necklace", CurioLootCategories::isNecklace, slots);
        Debug.log("registered loot categories: %s, %s", ring, necklace);
    }

    public static LootCategory ring() {
        register();
        return ring;
    }

    public static LootCategory necklace() {
        register();
        return necklace;
    }

    private static boolean isRing(ItemStack stack) {
        return !stack.isEmpty() && stack.is(RING_TAG);
    }

    private static boolean isNecklace(ItemStack stack) {
        return !stack.isEmpty() && stack.is(NECKLACE_TAG);
    }
}
