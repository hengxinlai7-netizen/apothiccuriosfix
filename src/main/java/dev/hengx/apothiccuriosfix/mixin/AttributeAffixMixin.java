package dev.hengx.apothiccuriosfix.mixin;

import dev.hengx.apothiccuriosfix.command.DiagCommand;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AttributeAffix;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

/**
 * Two narrow extensions of {@link AttributeAffix}, both of which only ever add applicability that Apotheosis would
 * otherwise refuse, never remove any.
 *
 * <h2>1. Curio affixes apply to every curio slot (this mod's affixes only)</h2>
 * The target packs expose far more {@code curios:*} categories than this mod ships data for (53 vs 23), so an item
 * whose category is e.g. {@code curios:accessory} matches no numeric affix at all ({@code STAT=0} in
 * {@code /acf diag}) and can never roll one. Our {@code apothiccurios:*_plus} affixes are therefore declared
 * applicable to any {@code curios:*} category that has a value for the item's rarity. Armor and weapons are not
 * affected, and neither is any other mod's data (the namespace check).
 *
 * <p>This is also what keeps the affix <em>on</em> the item: an affix that does not apply to the item's own
 * category is dropped again when the stack is read back, which is why changing only the candidate list inside
 * {@code LootController} was not enough.</p>
 *
 * <h2>2. Rarities without any {@code values} entry borrow the highest known tier</h2>
 * Rarities added by packs ({@code apotheotic_additions:esoteric}, ...) appear in loot rules but in no affix
 * {@code values} block, so every STAT rule of such a rarity finds nothing and logs
 * {@code Failed to execute LootRule .../esoteric/STAT/1.0!}. When an affix has no value for the requested rarity,
 * the highest tier it does have is used instead - for the applicability check ({@code canApplyTo}) and for the
 * number itself ({@link #apothiccuriosfix$borrowRarity}). This part is not limited to this mod's affixes: it fixes
 * the same gap for every numeric affix of the pack, which is what makes esoteric armor roll again too.
 */
@Mixin(value = AttributeAffix.class, remap = false)
public abstract class AttributeAffixMixin {

    @Shadow
    @Final
    protected Set<LootCategory> types;

    @Shadow
    @Final
    protected Map<LootRarity, ?> values;

    @Inject(method = "canApplyTo", at = @At("HEAD"), cancellable = true, require = 0)
    private void apothiccuriosfix$extendApplicability(ItemStack stack, LootCategory category, LootRarity rarity,
                                                     CallbackInfoReturnable<Boolean> cir) {
        try {
            if (category == null || category.isNone() || rarity == null || types == null || values == null) {
                return;
            }
            boolean declared = types.contains(category);
            boolean priceKnown = values.containsKey(rarity);
            if (declared && !priceKnown && effectiveRarity(rarity) != null) {
                // 2) this affix knows the category but not this pack rarity: borrow a tier
                cir.setReturnValue(true);
                return;
            }
            if (declared) {
                return;
            }
            String target = category.getName();
            if (target == null || !target.startsWith("curios:")) {
                return; // armor / weapons / everything else: untouched
            }
            if (effectiveRarity(rarity) == null) {
                return;
            }
            if (!isOurAffix()) {
                return; // 1) cross-slot is only for the affixes bundled with this mod
            }
            for (LootCategory candidate : types) {
                String name = candidate == null ? null : candidate.getName();
                if (name != null && name.startsWith("curios:")) {
                    cir.setReturnValue(true);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // never break a roll or a tooltip: fall back to Apotheosis' answer
        }
    }

    /** Maps a rarity this affix has no value for onto the highest tier it does have. */
    @ModifyVariable(method = { "addModifiers", "getDescription", "getAugmentingText" },
            at = @At("HEAD"), argsOnly = true, index = 2, require = 0)
    private LootRarity apothiccuriosfix$borrowRarity(LootRarity rarity) {
        try {
            if (rarity == null || values == null || values.containsKey(rarity)) {
                return rarity;
            }
            LootRarity fallback = effectiveRarity(rarity);
            return fallback == null ? rarity : fallback;
        } catch (Throwable ignored) {
            return rarity;
        }
    }

    /** The rarity itself when this affix has a value for it, else the highest known tier it does have. */
    @Nullable
    private LootRarity effectiveRarity(LootRarity rarity) {
        if (rarity == null || values == null) {
            return null;
        }
        if (values.containsKey(rarity)) {
            return rarity;
        }
        for (String candidate : new String[] { "apotheosis:ancient", "apotheosis:mythic", "apotheosis:epic",
                "apotheosis:rare", "apotheosis:uncommon", "apotheosis:common" }) {
            LootRarity known = RarityRegistry.INSTANCE.getValue(new ResourceLocation(candidate));
            if (known != null && values.containsKey(known)) {
                return known;
            }
        }
        return null;
    }

    private boolean isOurAffix() {
        ResourceLocation id = ((Affix) (Object) this).getId();
        return id != null && id.getNamespace().equals(DiagCommand.AFFIX_NAMESPACE)
                && id.getPath().endsWith("_plus");
    }
}
