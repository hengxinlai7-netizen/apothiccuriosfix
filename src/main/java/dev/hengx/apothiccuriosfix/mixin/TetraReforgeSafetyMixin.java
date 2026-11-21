package dev.hengx.apothiccuriosfix.mixin;

import dev.shadowsoffire.apotheosis.adventure.loot.LootController;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tetra 物品重铸的<b>保险丝</b>：重铸前后比对 Tetra 相关 NBT，丢了就补回去。
 *
 * <p>Tetra 把模块数据放在 {@code tetra} 子标签里。神化重铸会重写整个物品 NBT，
 * 一旦把 {@code tetra} 覆盖掉，物品就废了。这里在 {@code createLootItem} 入口
 * 记下原始 {@code tetra} 快照，在返回前检查结果物品：</p>
 *
 * <ul>
 *   <li>结果里<b>没有</b> {@code tetra} → 把快照写回去；</li>
 *   <li>结果里有但键数量变少 → 用快照补齐缺失的键，已有的不覆盖。</li>
 * </ul>
 *
 * <p>只处理带 {@code tetra} 标签的物品，其它物品完全不受影响。</p>
 */
@Mixin(value = LootController.class, remap = false)
public class TetraReforgeSafetyMixin {

    private static final String TETRA = "tetra";

    @Inject(
        method = "createLootItem(Lnet/minecraft/world/item/ItemStack;Ldev/shadowsoffire/apotheosis/adventure/loot/LootRarity;Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("HEAD"),
        remap = false,
        require = 0)
    private static void apothiccuriosfix$snapshot(ItemStack input, LootRarity rarity,
        RandomSource rand, CallbackInfoReturnable<ItemStack> cir) {
        if (input == null || input.isEmpty()) {
            return;
        }
        CompoundTag tag = input.getTag();
        if (tag == null || !tag.contains(TETRA, Tag.TAG_COMPOUND)) {
            return;
        }
        SNAPSHOT.set(tag.getCompound(TETRA).copy());
    }

    @Inject(
        method = "createLootItem(Lnet/minecraft/world/item/ItemStack;Ldev/shadowsoffire/apotheosis/adventure/loot/LootRarity;Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("RETURN"),
        remap = false,
        require = 0)
    private static void apothiccuriosfix$restore(ItemStack input, LootRarity rarity,
        RandomSource rand, CallbackInfoReturnable<ItemStack> cir) {
        CompoundTag snap = SNAPSHOT.get();
        SNAPSHOT.remove();
        if (snap == null) {
            return;
        }
        try {
            ItemStack out = cir.getReturnValue();
            if (out == null || out.isEmpty()) {
                return;
            }
            CompoundTag tag = out.getOrCreateTag();
            if (!tag.contains(TETRA, Tag.TAG_COMPOUND)) {
                tag.put(TETRA, snap.copy());
                System.out.println("[TetraSafety] 重铸丢失 tetra 数据,已回填");
                return;
            }
            CompoundTag cur = tag.getCompound(TETRA);
            boolean patched = false;
            for (String key : snap.getAllKeys()) {
                if (!cur.contains(key)) {
                    cur.put(key, snap.get(key).copy());
                    patched = true;
                }
            }
            if (patched) {
                System.out.println("[TetraSafety] 重铸丢失部分 tetra 键,已补齐");
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 静默
        }
    }

    private static final ThreadLocal<CompoundTag> SNAPSHOT = new ThreadLocal<>();
}