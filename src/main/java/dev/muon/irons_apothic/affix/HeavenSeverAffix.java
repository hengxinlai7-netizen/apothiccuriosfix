package dev.muon.irons_apothic.affix;

import java.util.Map;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.placebo.util.StepFunction;
import net.minecraft.world.item.ItemStack;

/**
 * 「天削」——技能词条。造成伤害时永久削减目标全属性,每层 -2%,上限 10 层(-20%)。
 * 逻辑见 {@code dev.hengx.apothiccuriosfix.event.HeavenSeverHandler}。
 */
public class HeavenSeverAffix extends Affix {

    public static final Codec<HeavenSeverAffix> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            LootRarity.mapCodec(StepFunction.CODEC).fieldOf("values").forGetter(a -> a.values),
            LootCategory.SET_CODEC.optionalFieldOf("types", Set.of()).forGetter(a -> a.types)
    ).apply(inst, HeavenSeverAffix::new));

    protected final Map<LootRarity, StepFunction> values;
    protected final Set<LootCategory> types;

    public HeavenSeverAffix(Map<LootRarity, StepFunction> values, Set<LootCategory> types) {
        super(AffixType.ABILITY);
        this.values = values;
        this.types = Set.copyOf(types);
    }

    @Override
    public Codec<? extends Affix> getCodec() {
        return CODEC;
    }

    @Override
    public boolean canApplyTo(ItemStack stack, LootCategory category, LootRarity rarity) {
        return this.types.contains(category) && this.values.containsKey(rarity);
    }

    public Map<LootRarity, StepFunction> values() {
        return this.values;
    }

    public Set<LootCategory> types() {
        return this.types;
    }
}