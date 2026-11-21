package dev.muon.irons_apothic.affix;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.muon.irons_apothic.util.Debug;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.placebo.util.StepFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reduces the mana cost of Iron's Spells casts by a percentage, optionally restricted to a set of schools.
 *
 * <pre>
 * {
 *   "type": "irons_apothic:mana_cost",
 *   "schools": [ "irons_spellbooks:fire" ],          // optional, empty = every school
 *   "values": { "rare": { "min": 0.06, "steps": 2, "step": 0.02 } },
 *   "types": [ "ring", "necklace" ]
 * }
 * </pre>
 *
 * <p>The reduction itself is applied by {@code IronsManaCostHandler} on Iron's {@code SpellOnCastEvent}:
 * 1.20.1's {@code AbstractSpell#castSpell} subtracts {@code SpellOnCastEvent#getManaCost()} when it spends
 * mana and {@code SpellOnCastEvent#setManaCost(int)} is public, so no mixin is needed.</p>
 *
 * <p>This class deliberately contains no reference to any Iron's Spellbooks type (schools are handled as
 * plain {@link ResourceLocation}s) so the affix can be decoded and registered even when Iron's Spellbooks
 * is not installed.</p>
 */
public class ManaCostAffix extends Affix {

    public static final Codec<ManaCostAffix> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            LootRarity.mapCodec(StepFunction.CODEC).fieldOf("values").forGetter(a -> a.values),
            LootCategory.SET_CODEC.optionalFieldOf("types", Set.of()).forGetter(a -> a.types),
            ResourceLocation.CODEC.optionalFieldOf("school").forGetter(ManaCostAffix::singleSchoolField),
            ResourceLocation.CODEC.listOf().optionalFieldOf("schools", List.of()).forGetter(a -> a.schoolIds)
    ).apply(inst, ManaCostAffix::create));

    /** Reductions are capped so a full set of gear can never make spells free. */
    public static final float MAX_REDUCTION = 0.90F;

    protected final Map<LootRarity, StepFunction> values;
    protected final Set<LootCategory> types;
    protected final List<ResourceLocation> schoolIds;

    protected ManaCostAffix(Map<LootRarity, StepFunction> values, Set<LootCategory> types, List<ResourceLocation> schoolIds) {
        super(AffixType.STAT);
        this.values = values;
        this.types = types;
        this.schoolIds = List.copyOf(schoolIds);
    }

    private static ManaCostAffix create(Map<LootRarity, StepFunction> values, Set<LootCategory> types,
                                        Optional<ResourceLocation> singleSchool, List<ResourceLocation> schools) {
        List<ResourceLocation> ids = new ArrayList<>();
        singleSchool.ifPresent(ids::add);
        for (ResourceLocation id : schools) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return new ManaCostAffix(values, types, ids);
    }

    /** {@code "school"} is only written when the affix is restricted to exactly one school. */
    protected Optional<ResourceLocation> singleSchoolField() {
        return this.schoolIds.size() == 1 ? Optional.of(this.schoolIds.get(0)) : Optional.empty();
    }

    @Override
    public Codec<? extends Affix> getCodec() {
        return CODEC;
    }

    // ------------------------------------------------------------------ data accessors

    public Map<LootRarity, StepFunction> values() {
        return this.values;
    }

    public Set<LootCategory> types() {
        return this.types;
    }

    public List<ResourceLocation> schoolIds() {
        return this.schoolIds;
    }

    public float getReductionPercent(LootRarity rarity, float level) {
        StepFunction function = this.values.get(rarity);
        return function == null ? 0.0F : function.get(level);
    }

    public boolean matchesSchool(@Nullable ResourceLocation schoolId) {
        return this.schoolIds.isEmpty() || (schoolId != null && this.schoolIds.contains(schoolId));
    }

    // ------------------------------------------------------------------ affix behaviour

    @Override
    public boolean canApplyTo(ItemStack stack, LootCategory category, LootRarity rarity) {
        if (category == null || category.isNone()) {
            return false;
        }
        if (!this.values.containsKey(rarity)) {
            return false;
        }
        return this.types.isEmpty() || this.types.contains(category);
    }

    // ------------------------------------------------------------------ tooltip

    @Override
    public MutableComponent getDescription(ItemStack stack, LootRarity rarity, float level) {
        return Component.translatable("affix.irons_apothic.mana_cost.desc",
                schoolComponent(), fmt(getReductionPercent(rarity, level) * 100.0F));
    }

    @Override
    public Component getAugmentingText(ItemStack stack, LootRarity rarity, float level) {
        MutableComponent description = this.getDescription(stack, rarity, level);
        StepFunction function = this.values.get(rarity);
        if (function == null) {
            return description;
        }
        Component min = Component.literal(fmt(function.min() * 100.0F) + "%");
        Component max = Component.literal(fmt(function.max() * 100.0F) + "%");
        return description.append(valueBounds(min, max));
    }

    protected Component schoolComponent() {
        if (this.schoolIds.isEmpty()) {
            return Component.translatable("affix.irons_apothic.school.any");
        }
        MutableComponent result = Component.empty();
        for (int i = 0; i < this.schoolIds.size(); i++) {
            if (i > 0) {
                result.append(Component.literal(", "));
            }
            ResourceLocation id = this.schoolIds.get(i);
            result.append(Component.translatable("school." + id.getNamespace() + "." + id.getPath()));
        }
        return result;
    }

    // ------------------------------------------------------------------ calculation

    /**
     * Applies a (possibly out of range) total reduction to a base mana cost. Pure and static so it can be
     * checked without a running game.
     */
    public static int applyReduction(int manaCost, float totalReduction) {
        float clamped = Math.max(0.0F, Math.min(totalReduction, MAX_REDUCTION));
        return Math.max(0, Math.round(manaCost * (1.0F - clamped)));
    }

    /** Sums every {@code ManaCostAffix} on the given gear stack that applies to the given school. */
    public static float reductionOnStack(ItemStack stack, @Nullable ResourceLocation schoolId) {
        if (stack.isEmpty()) {
            return 0.0F;
        }
        float reduction = 0.0F;
        List<AffixInstance> affixes;
        try {
            affixes = AffixHelper.streamAffixes(stack).toList();
        } catch (RuntimeException ex) {
            Debug.log("failed to read affixes of %s: %s", stack, ex);
            return 0.0F;
        }
        for (AffixInstance instance : affixes) {
            if (!instance.isValid()) {
                continue;
            }
            Affix affix = instance.affix().get();
            if (!(affix instanceof ManaCostAffix manaCost) || !manaCost.matchesSchool(schoolId)) {
                continue;
            }
            LootRarity rarity = instance.rarity().get();
            if (rarity == null) {
                continue;
            }
            reduction += manaCost.getReductionPercent(rarity, instance.level());
        }
        return reduction;
    }

    public static float totalReduction(Iterable<ItemStack> stacks, @Nullable ResourceLocation schoolId) {
        float reduction = 0.0F;
        for (ItemStack stack : stacks) {
            reduction += reductionOnStack(stack, schoolId);
        }
        return reduction;
    }
}
