package dev.hengx.apothiccuriosfix.mixin;

import dev.hengx.apothiccuriosfix.category.CurioLootCategories;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Creates a missing {@code curios:<slot>} loot category <em>when it is asked for</em>.
 *
 * <p>{@code LootCategory.CODEC} is {@code ExtraCodecs.stringResolverCodec(... , LootCategory::byId)} (verified
 * with {@code javap -v}: bootstrap method 4 is a method handle on {@code LootCategory.byId}). Every affix / gem
 * {@code types} entry is decoded through it, so this is the exact method that produced</p>
 *
 * <pre>
 * Codec failure for type affixes, message: Unknown element name:curios:an_focus
 * </pre>
 *
 * <p>Registering from here - instead of from a data pack reload listener - is the only ordering that can be
 * guaranteed: Apotheosis' affix registry, Curios' own {@code data/curios/slots/*.json} loader and this mod's
 * listeners are all started in mod-load order, so a listener of this mod may well run after the affix files
 * have already been parsed. A category created by {@code byId} also cannot be "too early": it is created in the
 * same call stack that needs it, and a name that is already registered is left completely alone.</p>
 *
 * <p>Non-fatal by construction: {@code require = 0} and a config-level {@code "required": false} mean a future
 * Apotheosis rename degrades to "no lazy registration" instead of an {@code InvalidInjectionException}. The
 * base-slot set from the mod constructor keeps working in that case, and the self test fails loudly.</p>
 */
@Mixin(value = LootCategory.class, remap = false)
public class LootCategoryMixin {

    @Inject(method = "byId", at = @At("HEAD"), require = 0)
    private static void apothiccuriosfix$ensureCurioCategory(String name, CallbackInfoReturnable<LootCategory> cir) {
        try {
            CurioLootCategories.ensureFromId(name);
        } catch (Throwable ignored) {
            // A lookup must never throw: returning null keeps Apotheosis' normal "unknown element" path intact.
        }
    }
}
