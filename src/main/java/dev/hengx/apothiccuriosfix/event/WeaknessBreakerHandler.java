package dev.hengx.apothiccuriosfix.event;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 「弱点破坏」：对<b>无护甲</b>目标造成的伤害 +50%。
 *
 * <p>判定「无护甲」用 {@link LivingEntity#getArmorValue()}——护甲值为 0 即视为无护甲
 * （裸装、只穿无护甲值的衣服、护甲被打光都算）。</p>
 *
 * <p>加成是**乘法**：{@code amount *= 1.5}，对近战、法术、弓、召唤物一视同仁。</p>
 */
public final class WeaknessBreakerHandler {

    public static final String AFFIX_ID = "irons_apothic:weakness_breaker";
    public static final float BONUS = 0.5F;

    private WeaknessBreakerHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(LivingHurtEvent event) {
        try {
            LivingEntity target = event.getEntity();
            if (target == null || target.level().isClientSide || target.isDeadOrDying()) {
                return;
            }
            // 无护甲方可触发
            if (target.getArmorValue() > 0) {
                return;
            }
            Entity raw = event.getSource().getEntity();
            if (!(raw instanceof Player)) {
                raw = event.getSource().getDirectEntity();
            }
            if (!(raw instanceof Player)) {
                return;
            }
            Player player = (Player) raw;
            if (!holds(player)) {
                return;
            }
            event.setAmount(event.getAmount() * (1.0F + BONUS));
        }
        catch (RuntimeException | LinkageError ignored) {
            // 静默
        }
    }

    private static boolean holds(Player player) {
        return has(player.getMainHandItem()) || has(player.getOffhandItem());
    }

    private static boolean has(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper.getAffixes(stack)
                .keySet().stream()
                .anyMatch(h -> h.getId().toString().equals(AFFIX_ID));
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
