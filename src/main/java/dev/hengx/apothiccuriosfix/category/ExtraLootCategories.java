package dev.hengx.apothiccuriosfix.category;

import java.util.function.Predicate;

import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 补齐 {@code irons_apothic} 数据用到、但游戏里并不存在的两个战利品类目：
 * {@code staff} 与 {@code spellbook}。
 *
 * <h2>为什么必须补</h2>
 * <p>神化在数据包重载时用 {@link LootCategory#SET_CODEC} 解析词条 JSON 的 {@code types} 数组，
 * 名字要到 {@link LootCategory#BY_ID} 里查。只要有一个名字查不到，
 * {@code stringResolverCodec} 就抛 {@code Unknown element name:staff}，
 * <b>整个词条文件作废</b>。</p>
 *
 * <p>{@code irons_apothic} 的属性词条全都写着 {@code "types": [ "staff" ]}
 * （原有的 {@code max_mana} 等以及本模组新增的 11 个），
 * 所以日志里每个文件都 "Failed parsing affixes file"，
 * 连带 MMT 武器映射到 {@code staff} 也失败（{@code byId("staff")} 返回 null）。</p>
 *
 * <h2>幂等</h2>
 * <p>{@code LootCategory.register} 对重复名字会抛异常，
 * 所以先查 {@link LootCategory#BY_ID}，已存在就跳过。</p>
 */
public final class ExtraLootCategories {

    private ExtraLootCategories() {
    }

    public static void register() {
        // 法杖:MMT 可改造法杖/巫法杖 + 名字里带 staff / wand 的物品
        registerOne("staff", stack -> matches(stack, "staff", "wand", "_rod"));
        // 魔法书:铁魔法的法术书
        registerOne("spellbook", stack -> matches(stack, "spellbook", "spell_book"));
        // 轻武器:剑类 & 匕首类
        registerOne("light_weapon", stack -> matches(stack, "sword", "dagger", "rapier", "katana"));
        System.out.println("[ExtraLootCategories] staff / spellbook 类目已就绪");
    }

    private static void registerOne(String id, Predicate<ItemStack> predicate) {
        try {
            if (LootCategory.BY_ID.containsKey(id)) {
                return;   // 已存在(ApothicCurios 或别的模组注册过)
            }
            LootCategory.register(null, id, predicate,
                new EquipmentSlot[] { EquipmentSlot.MAINHAND });
        }
        catch (RuntimeException | LinkageError e) {
            System.out.println("[ExtraLootCategories] 注册 " + id + " 失败: " + e);
        }
    }

    private static boolean matches(ItemStack stack, String... needles) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        for (String n : needles) {
            if (path.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
