package dev.hengx.apothiccuriosfix.mixin;

import dev.hengx.apothiccuriosfix.category.CurioLootCategories;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootController;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry;
import dev.shadowsoffire.placebo.reload.DynamicHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fills an empty affix candidate pool so that a loot rule finds something instead of logging
 * {@code Failed to execute LootRule ...} and leaving its slot empty. Every step only runs when Apotheosis' own
 * answer is empty.
 *
 * <ol>
 *     <li><b>Curio slot the mod has no data for</b> - the packs expose far more {@code curios:*} categories than this
 *         mod ships files for (53 vs 23), so e.g. {@code curios:accessory} has {@code STAT=0} and can never roll a
 *         numeric affix. For those items the {@code curios:charm} affixes are used. Non-curio categories are never
 *         touched here.</li>
 *     <li><b>{@link AffixType#ANCIENT} is a dead type</b> - no {@code Affix} subclass of 7.4.8 constructs it and no
 *         codec maps to it ({@code javap}), yet {@code apotheosis:ancient} starts with
 *         {@code {"type":"ancient","chance":1.0}} and no backup. Fulfilled from the numeric pool instead.</li>
 *     <li><b>Rarity tier missing</b> - pack rarities such as {@code apotheotic_additions:esoteric} appear in loot
 *         rules but in no affix {@code values} block. The same query is repeated with the higher vanilla tiers
 *         ({@code ancient}, {@code mythic}, ...); {@code AttributeAffixMixin} makes the affix use that tier's number,
 *         so the candidate is real and stays on the item.</li>
 * </ol>
 *
 * <p>{@code LootController.getAvailableAffixes} derives the category from the stack, so the alternatives are
 * computed with the same three-step filter Apotheosis uses: {@code byType(type)} → {@code canApplyTo(stack,
 * category, rarity)} → {@code !existing.contains(holder)}.</p>
 */
@Mixin(value = LootController.class, remap = false)
public class LootControllerMixin {

    private static final String[] RARITY_FALLBACKS = {
            "apotheosis:ancient", "apotheosis:mythic", "apotheosis:epic",
            "apotheosis:rare", "apotheosis:uncommon", "apotheosis:common"
    };

    @Inject(method = "getAvailableAffixes", at = @At("RETURN"), cancellable = true, require = 0)
    private static void apothiccuriosfix$fillEmptyPool(ItemStack stack, LootRarity rarity,
                                                       Set<DynamicHolder<? extends Affix>> existing, AffixType type,
                                                       CallbackInfoReturnable<List<DynamicHolder<? extends Affix>>> cir) {
        try {
            List<DynamicHolder<? extends Affix>> found = cir.getReturnValue();
            if (found != null && !found.isEmpty()) {
                return;
            }
            LootCategory category = LootCategory.forItem(stack);
            if (category == null || category.isNone() || rarity == null || type == null) {
                return;
            }
            String categoryName = category.getName();
            boolean curio = categoryName != null && categoryName.startsWith(CurioLootCategories.PREFIX);
            LootCategory charm = LootCategory.BY_ID.get(CurioLootCategories.PREFIX + "charm");
            LootCategory alt = curio && charm != null && charm != category ? charm : null;
            AffixType effective = type == AffixType.ANCIENT ? AffixType.STAT : type;

            if (trySet(cir, query(effective, stack, alt, rarity, existing))
                    || trySet(cir, query(effective, stack, category, rarity, existing))) {
                return;
            }
            for (String fallbackId : RARITY_FALLBACKS) {
                LootRarity fallback = RarityRegistry.INSTANCE.getValue(new ResourceLocation(fallbackId));
                if (fallback == null || fallback == rarity) {
                    continue;
                }
                if (trySet(cir, query(effective, stack, alt, fallback, existing))
                        || trySet(cir, query(effective, stack, category, fallback, existing))) {
                    return;
                }
            }
        } catch (Throwable ignored) {
            // never break a loot roll: on any problem keep Apotheosis' own answer
        }
    }

    private static boolean trySet(CallbackInfoReturnable<List<DynamicHolder<? extends Affix>>> cir,
                                  List<DynamicHolder<? extends Affix>> list) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        cir.setReturnValue(list);
        return true;
    }

    /** Mirror of {@code LootController.getAvailableAffixes} for an explicit category (null means "skip"). */
    private static List<DynamicHolder<? extends Affix>> query(AffixType type, ItemStack stack, LootCategory category,
                                                              LootRarity rarity,
                                                              Set<DynamicHolder<? extends Affix>> existing) {
        List<DynamicHolder<? extends Affix>> out = new ArrayList<>();
        if (type == null || category == null || rarity == null) {
            return out;
        }
        try {
            for (DynamicHolder<? extends Affix> holder : AffixHelper.byType(type)) {
                Affix affix = holder.getOptional().orElse(null);
                if (affix == null || existing.contains(holder)) {
                    continue;
                }
                if (affix.canApplyTo(stack, category, rarity)) {
                    out.add(holder);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // a single broken affix must not break a roll
        }
        return out;
    }
}

