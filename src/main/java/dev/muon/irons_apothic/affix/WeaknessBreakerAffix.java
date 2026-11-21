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
 * 「弱点破坏」——技能词条。对<b>无护甲</b>目标造成的伤害增加 50%。
 *
 * <p>最低稀有度：蓝色（rare）。适用：轻武器 + 法杖。</p>
 *
 * <p>伤害加成由 {@code dev.hengx.apothiccuriosfix.event.WeaknessBreakerHandler} 在
 * {@code LivingHurtEvent} 里施加。</p>
 */
public class WeaknessBreakerAffix extends Affix {

    public static final Codec<WeaknessBreakerAffix> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            LootRarity.mapCodec(StepFunction.CODEC).fieldOf("values").forGetter(a -> a.values),
            LootCategory.SET_CODEC.optionalFieldOf("types", Set.of()).forGetter(a -> a.types)
    ).apply(inst, WeaknessBreakerAffix::new));

    protected final Map<LootRarity, StepFunction> values;
    protected final Set<LootCategory> types;

    public WeaknessBreakerAffix(Map<LootRarity, StepFunction> values, Set<LootCategory> types) {
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
