package dev.hengx.apothiccuriosfix.mixin;

import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 Tetra 系模组的物品能进神化的重铸台。
 *
 * <p>Tetra 的物品是模块化的，{@code LootCategory.forItem} 对它们返回 {@link LootCategory#NONE}，
 * 所以重铸台直接拒收。这里按<b>精确 id 白名单</b>归类，不做通配匹配。</p>
 *
 * <ul>
 *   <li><b>more_mod_tetra</b>(MMT) 武器：法杖、巫法杖 → {@code staff}；弓 → {@code bow}</li>
 *   <li><b>tetra_spell_book</b>：4 本魔法书 → {@code spellbook}；法术弓/弩 → {@code bow}/{@code crossbow}；
 *       附魔师之剑 → {@code light_weapon}</li>
 *   <li><b>MMT 饰品</b>：11 个 {@code curios_*} → 对应的 {@code curios:<slot>}，
 *       由 {@link #curioSlot} 显式列表决定，不在名单里的一律不动</li>
 * </ul>
 *
 * <p>本 Mixin 只改变"物品被判定成哪个类目"，不触碰物品自身的 Tetra 模块 NBT 或 Curios 槽定义；
 * 重铸后若 Tetra 数据丢失，由 {@link TetraReforgeSafetyMixin} 负责回滚。</p>
 */
@Mixin(value = LootCategory.class, remap = false)
public class MmTetraLootCategoryMixin {

    @Inject(method = "forItem", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void apothiccuriosfix$tetraItems(ItemStack stack,
        CallbackInfoReturnable<LootCategory> cir) {
        try {
            if (!dev.hengx.apothiccuriosfix.AcfConfig.tetraCompat()) {
                return;   // 3.0.0:Tetra 兼容已关闭
            }
            if (stack == null || stack.isEmpty()) {
                return;
            }
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key == null) {
                return;
            }
            String id = key.toString();

            // ---- 武器 ----
            boolean ironOnly = dev.hengx.apothiccuriosfix.AcfConfig.ironOnly();

            // ---- 法杖(两种模式都映射) ----
            if (isMmStaff(id)) {
                set(cir, "staff");
                return;
            }

            // ---- iron_only:只保留法杖与魔法书,其余一律不动 ----
            if (ironOnly) {
                if (isSpellBook(id)) {
                    set(cir, "spellbook");
                }
                return;
            }
            if (id.equals("more_mod_tetra:tetra_mmt_bow")
                || id.equals("tetra_spell_book:tetra_ars_spell_bow")) {
                cir.setReturnValue(LootCategory.BOW);
                return;
            }
            if (id.equals("tetra_spell_book:tetra_ars_spell_crossbow")) {
                cir.setReturnValue(LootCategory.CROSSBOW);
                return;
            }
            // tetra_spell_book:tetra_enchanters_sword 已排除:
            // 它的 fuller/base_fuller 图纸缺失,悬停时 Tetra 每帧抛 InvalidSchematicException,
            // 不仅卡死 tooltip,重铸也只会让坏数据更多。

            // ---- 魔法书 ----
            if (isSpellBook(id)) {
                set(cir, "spellbook");
                return;
            }

            // 2.11.1A:MMT 饰品映射暂时关闭(排查 tooltip 卡死)
            // String slot = curioSlot(id);
            // if (slot != null) { set(cir, slot); }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 放行原逻辑
        }
    }

    /**
     * 3.1.0:饰品不吃神化。
     *
     * <p>{@code curios:*} 类目本身必须注册(否则词条 JSON 里的 {@code types} 解析失败、整份文件作废),
     * 但只要它们能作为 {@code forItem} 的结果返回,神化就会给饰品生成词条与宝石孔。
     * 这里在 {@code forItem} 返回后把结果抹成 {@link LootCategory#NONE},
     * 效果就是:类目还在(数据能解析),但物品不再被神化。</p>
     */
    @Inject(method = "forItem", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void apothiccuriosfix$denyCurioReforge(ItemStack stack,
        CallbackInfoReturnable<LootCategory> cir) {
        try {
            if (dev.hengx.apothiccuriosfix.AcfConfig.curiosReforgeable()) {
                return;   // 配置允许 ⇒ 不处理
            }
            LootCategory result = cir.getReturnValue();
            if (result == null || result == LootCategory.NONE) {
                return;
            }
            for (var e : LootCategory.BY_ID.entrySet()) {
                if (e.getValue() == result && e.getKey().startsWith("curios:")) {
                    cir.setReturnValue(LootCategory.NONE);
                    return;
                }
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 放行
        }
    }
    /** 铁魔法法杖 + MMT 可改造法杖/巫法杖。 */
    private static boolean isMmStaff(String id) {
        if (id.equals("more_mod_tetra:tetra_iron_staff")
            || id.equals("more_mod_tetra:tetra_dark_wand")) {
            return true;
        }
        return id.startsWith("irons_spellbooks:")
            && (id.endsWith("_staff") || id.equals("irons_spellbooks:staff_of_the_nines"))
            && !id.endsWith("_haft");
    }

    private static boolean isSpellBook(String id) {
        return id.equals("tetra_spell_book:tetra_spell_book")
            || id.equals("tetra_spell_book:tetra_novice_spell_book")
            || id.equals("tetra_spell_book:tetra_apprentice_spell_book")
            || id.equals("tetra_spell_book:tetra_archmage_spell_book");
    }

    /** MMT 饰品的 Curios 槽。返回 null 表示不处理。 */
    private static String curioSlot(String id) {
        if (!id.startsWith("more_mod_tetra:curios_")) {
            return null;
        }
        switch (id) {
            case "more_mod_tetra:curios_ring":      return "curios:ring";
            case "more_mod_tetra:curios_necklace":  return "curios:necklace";
            case "more_mod_tetra:curios_amulet":    return "curios:necklace";
            case "more_mod_tetra:curios_crown":     return "curios:head";
            case "more_mod_tetra:curios_glove":     return "curios:hands";
            case "more_mod_tetra:curios_bracelet":  return "curios:bracelet";
            case "more_mod_tetra:curios_shoes":     return "curios:feet";
            case "more_mod_tetra:curios_jetpack":   return "curios:back";
            case "more_mod_tetra:curios_emblem":    return "curios:charm";
            case "more_mod_tetra:curios_heart_protecting_mirror": return "curios:charm";
            case "more_mod_tetra:curios_totem_of_undying":        return "curios:charm";
            default: return null;
        }
    }

    /** 按名字取类目；取不到就什么也不做(保留原逻辑)。 */
    private static void set(CallbackInfoReturnable<LootCategory> cir, String name) {
        LootCategory cat = LootCategory.byId(name);
        if (cat != null) {
            cir.setReturnValue(cat);
        }
    }
}