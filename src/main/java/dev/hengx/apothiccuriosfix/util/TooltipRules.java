package dev.hengx.apothiccuriosfix.util;

import dev.hengx.apothiccuriosfix.category.CurioLootCategories;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import net.minecraft.world.item.ItemStack;

/**
 * Client tooltip rule: hide the per-{@code EquipmentSlot} attribute block for <em>any</em> curio.
 *
 * <p>ApothicAttributes rebuilds the vanilla "When in main hand / when on feet / ..." block in
 * {@code AttributesLibClient#tooltips} and delegates the actual lines to {@code applyModifierTooltips}, which
 * queries the stack's attribute modifiers for every slot. Curios never occupy a vanilla slot, so every one of
 * those groups is meaningless for them - and for a curio the {@code MAINHAND}/{@code OFFHAND} pair even
 * collapses into the "在任意手时 / When in Any Hand" group the players complained about.</p>
 *
 * <p>The condition is deliberately broad in one direction and narrow in the other:</p>
 * <ul>
 *     <li><b>hidden</b> when the item resolves to a {@code curios:*} loot category - no matter which mod the
 *         affixes come from ({@code apotheosis:attribute}, {@code apothiccurios:*}, Apotheotic Additions, ...)
 *         and no matter whether it carries affixes at all. Version 1.20.1a additionally required
 *         {@code AffixHelper.hasAffixes}, which is why an affixed charm whose category had been dropped by the
 *         data pack still showed the block;</li>
 *     <li><b>hidden</b> when the item carries one of the {@code curios:<slot>} item tags, as a fallback for
 *         packs that tag a trinket without any registered category matching it;</li>
 *     <li><b>untouched</b> for armor, weapons and every other non-curio item: neither check can match them.</li>
 * </ul>
 */
public final class TooltipRules {

    private TooltipRules() {
    }

    /** True when the stack is a curio: a {@code curios:*} loot category, or one of the curio item tags. */
    public static boolean isCurio(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            if (CurioLootCategories.isCurioCategory(LootCategory.forItem(stack))) {
                return true;
            }
            return CurioLootCategories.isCurioTagged(stack);
        } catch (RuntimeException | LinkageError ex) {
            // A tooltip must never throw: on any problem keep the vanilla behaviour.
            return false;
        }
    }

    /** True when the per-slot attribute block has to be replaced by the curio block for this stack. */
    public static boolean hidesAttributeSlotSections(ItemStack stack) {
        return isCurio(stack);
    }
}
