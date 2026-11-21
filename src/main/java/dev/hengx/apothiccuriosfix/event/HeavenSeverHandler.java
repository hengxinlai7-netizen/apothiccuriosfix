package dev.hengx.apothiccuriosfix.event;

import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 「天削」的运行时逻辑：造成伤害时永久削减目标全属性。
 *
 * <ul>
 *   <li>每次命中 +1 层，每层 <b>-2%</b>；</li>
 *   <li>上限 <b>10 层 = -20%</b>；</li>
 *   <li><b>永久</b>，不衰减、不恢复（层数存进目标的持久数据，重登/重载仍在）；</li>
 *   <li>作用于目标的<b>全部</b>可同步属性，乘区 {@code MULTIPLY_TOTAL}，固定 UUID 便于重算。</li>
 * </ul>
 *
 * <p>最大生命被削减时会把当前血量钳到新上限，避免出现"血条超过上限"。</p>
 */
public final class HeavenSeverHandler {

    public static final String NBT_KEY = "apothiccuriosfix_heaven_sever";
    public static final int MAX_STACKS = 10;
    public static final double PER_STACK = -0.02D;

    private static final UUID MODIFIER_ID =
        UUID.nameUUIDFromBytes("apothiccuriosfix:heaven_sever".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private HeavenSeverHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(LivingHurtEvent event) {
        try {
            LivingEntity target = event.getEntity();
            if (target == null || target.level().isClientSide) {
                return;
            }
            Entity raw = event.getSource().getEntity();
            if (!(raw instanceof Player)) {
                raw = event.getSource().getDirectEntity();
            }
            if (!(raw instanceof Player)) {
                return;
            }
            Player attacker = (Player) raw;
            if (!holdsHeavenSever(attacker)) {
                return;
            }
            apply(target, stacks(target) + 1);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 静默
        }
    }

    /** 攻击者主手/副手是否带「天削」词条。 */
    private static boolean holdsHeavenSever(Player player) {
        try {
            return hasAffix(player.getMainHandItem()) || hasAffix(player.getOffhandItem());
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean hasAffix(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper.getAffixes(stack)
            .keySet().stream()
            .anyMatch(h -> h.getId().toString().equals("irons_apothic:heaven_sever"));
    }

    public static int stacks(LivingEntity target) {
        CompoundTag tag = target.getPersistentData();
        return tag == null ? 0 : Math.min(MAX_STACKS, tag.getInt(NBT_KEY));
    }

    /** 写入层数并重算全部属性修饰符。 */
    public static void apply(LivingEntity target, int newStacks) {
        int stacks = Math.max(0, Math.min(MAX_STACKS, newStacks));
        CompoundTag tag = target.getPersistentData();
        if (tag == null) {
            return;
        }
        tag.putInt(NBT_KEY, stacks);
        double amount = PER_STACK * stacks;
        for (AttributeInstance inst : target.getAttributes().getSyncableAttributes()) {
            try {
                inst.removeModifier(MODIFIER_ID);
                if (stacks > 0) {
                    inst.addPermanentModifier(new AttributeModifier(MODIFIER_ID,
                        "apothiccuriosfix:heaven_sever", amount,
                        AttributeModifier.Operation.MULTIPLY_TOTAL));
                }
            }
            catch (RuntimeException | LinkageError ignored) {
                // 单个属性失败不影响其它
            }
        }
        // 最大生命被削后把血钳到新上限
        try {
            if (target.getHealth() > target.getMaxHealth()) {
                target.setHealth(target.getMaxHealth());
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 某些路径(如实体重载)需要按已存的层数重建修饰符。 */
    public static void restore(LivingEntity target) {
        int s = stacks(target);
        if (s > 0) {
            apply(target, s);
        }
    }
}
