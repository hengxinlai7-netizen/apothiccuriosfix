package dev.hengx.apothiccuriosfix.mixin;

import java.util.HashMap;
import java.util.Map;

import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootController;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.placebo.reload.DynamicHolder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 词条的<b>初始强化等级</b>随品质递增。
 *
 * <pre>
 *   白 common   0
 *   绿 uncommon 1
 *   蓝 rare     2
 *   紫 epic     3
 *   橙 mythic   4
 *   远古 ancient 5
 * </pre>
 *
 * <p>神化默认给所有词条 {@code level = 1}（{@link AffixInstance} 的第 4 个构造参数）。
 * 这里在 {@code LootController#createLootItem} 返回前，把每个词条按该物品的稀有度
 * 重建一次 {@link AffixInstance}：<b>只改 {@code level}，不动可强化次数</b>，
 * 也不增删任何词条。</p>
 *
 * <p>两个重载都挂：</p>
 * <ul>
 *   <li>3 参数 {@code (ItemStack, LootRarity, RandomSource)} —— 战利品生成；</li>
 *   <li>4 参数 {@code (ItemStack, LootCategory, LootRarity, RandomSource)} —— 重铸台使用。</li>
 * </ul>
 *
 * <p>幂等：重复执行只会把 level 再写成同一个值，不会累积。</p>
 */
@Mixin(value = LootController.class, remap = false)
public class AffixLevelByRarityMixin {

    @Inject(
        method = "createLootItem(Lnet/minecraft/world/item/ItemStack;Ldev/shadowsoffire/apotheosis/adventure/loot/LootRarity;Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("RETURN"),
        remap = false,
        require = 0)
    private static void apothiccuriosfix$levelByRarity3(ItemStack input, LootRarity rarity,
        RandomSource rand, CallbackInfoReturnable<ItemStack> cir) {
        apothiccuriosfix$apply(cir.getReturnValue(), rarity);
    }

    @Inject(
        method = "createLootItem(Lnet/minecraft/world/item/ItemStack;Ldev/shadowsoffire/apotheosis/adventure/loot/LootCategory;Ldev/shadowsoffire/apotheosis/adventure/loot/LootRarity;Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("RETURN"),
        remap = false,
        require = 0)
    private static void apothiccuriosfix$levelByRarity4(ItemStack input, LootCategory category,
        LootRarity rarity, RandomSource rand, CallbackInfoReturnable<ItemStack> cir) {
        apothiccuriosfix$apply(cir.getReturnValue(), rarity);
    }

    /** 把物品上每个词条的 level 改成该稀有度对应的初始等级。 */
    private static void apothiccuriosfix$apply(ItemStack out, LootRarity rarity) {
        // 3.0.0:默认关闭,由 config/apothiccuriosfix-common.toml →
        // affixLevel.affixLevelByRarityEnabled 控制
        if (!dev.hengx.apothiccuriosfix.AcfConfig.affixLevelByRarity()) {
            return;
        }
        try {
            if (out == null || out.isEmpty() || rarity == null) {
                return;
            }
            Map<DynamicHolder<? extends Affix>, AffixInstance> affixes = AffixHelper.getAffixes(out);
            if (affixes == null || affixes.isEmpty()) {
                return;
            }
            float level = rarity.ordinal();
            Map<DynamicHolder<? extends Affix>, AffixInstance> rebuilt = new HashMap<>();
            for (Map.Entry<DynamicHolder<? extends Affix>, AffixInstance> e : affixes.entrySet()) {
                AffixInstance old = e.getValue();
                if (old == null) {
                    continue;
                }
                if (old.level() == level) {
                    // 已经是对的值,原样保留,避免无谓重建
                    rebuilt.put(e.getKey(), old);
                    continue;
                }
                rebuilt.put(e.getKey(), new AffixInstance(e.getKey(), out, old.rarity(), level));
            }
            AffixHelper.setAffixes(out, rebuilt);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 任何异常都不影响原有流程
        }
    }
}
