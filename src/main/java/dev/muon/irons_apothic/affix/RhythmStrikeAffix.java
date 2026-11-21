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

/**
 * 「节奏打击」——技能词条。
 *
 * <p>10 秒内重复释放同一个法术时，每层 +10% 法强，可无限叠加；
 * 每次重复施放都会刷新 10 秒的计时。具体逻辑见 {@link RhythmStrikeHandler}。</p>
 *
 * <pre>
 * {
 *   "type": "irons_apothic:rhythm_strike",
 *   "values": { "rare": { "min": 0.10, "steps": 0 } },
 *   "types": [ "staff" ]
 * }
 * </pre>
 *
 * <p>{@code values} 里<b>只写 rare 及以上</b>，因此白色/绿色水晶刷不出这个词条。</p>
 */
public class RhythmStrikeAffix extends Affix {

    public static final Codec<RhythmStrikeAffix> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            LootRarity.mapCodec(StepFunction.CODEC).fieldOf("values").forGetter(a -> a.values),
            LootCategory.SET_CODEC.optionalFieldOf("types", Set.of()).forGetter(a -> a.types)
    ).apply(inst, RhythmStrikeAffix::new));

    protected final Map<LootRarity, StepFunction> values;
    protected final Set<LootCategory> types;

    public RhythmStrikeAffix(Map<LootRarity, StepFunction> values, Set<LootCategory> types) {
        super(AffixType.ABILITY);
        this.values = values;
        this.types = Set.copyOf(types);
    }

    @Override
    public Codec<? extends Affix> getCodec() {
        return CODEC;
    }

    @Override
    public boolean canApplyTo(net.minecraft.world.item.ItemStack stack, LootCategory category, LootRarity rarity) {
        return this.types.contains(category) && this.values.containsKey(rarity);
    }

    public Map<LootRarity, StepFunction> values() {
        return this.values;
    }

    public Set<LootCategory> types() {
        return this.types;
    }
}
