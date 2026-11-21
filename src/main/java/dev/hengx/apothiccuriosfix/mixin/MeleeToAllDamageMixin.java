package dev.hengx.apothiccuriosfix.mixin;

import dev.shadowsoffire.attributeslib.util.AttributesUtil;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让神化的「属性伤害」不再只对物理伤害生效。
 *
 * <h2>原版行为</h2>
 * <p>{@code dev.shadowsoffire.attributeslib.impl.AttributeEvents#meleeDamageAttributes} 里有一道闸门:</p>
 *
 * <pre>
 *   attacker = source.getEntity();
 *   if (attacker instanceof LivingEntity &amp;&amp; AttributesUtil.isPhysicalDamage(source)) {
 *       ... 依次结算 CURRENT_HP_DAMAGE / FIRE_DAMAGE / COLD_DAMAGE / ... ...
 *   }
 * </pre>
 *
 * <p>也就是说：<b>只要不是物理伤害，整段属性伤害结算会被直接跳过</b>。
 * 所以魔法、火焰、药水、爆炸、召唤物打出来的伤害都吃不到这些加成。</p>
 *
 * <h2>本 Mixin 做什么</h2>
 * <p>在<b>这个方法内部</b>改写那一次 {@code isPhysicalDamage} 调用的返回值：</p>
 * <ul>
 *   <li>本来就是物理伤害 → 原样 {@code true}；</li>
 *   <li>否则只要"有活体攻击者"且"不是环境/无来源伤害" → 也返回 {@code true}，
 *       让属性伤害照常结算；</li>
 *   <li>环境伤害（摔落/岩浆/窒息/虚空）、无主伤害（NO_ANGER）仍然排除。</li>
 * </ul>
 *
 * <p><b>递归防护</b>：神化自己已有 {@code AttributeEvents.noRecurse} 静态标记，
 * 每次进入该方法先置位、结束清除，所以"词条触发词条"不会发生，这里无需重复处理。</p>
 *
 * <p>只重定向 {@code meleeDamageAttributes} 里的这一次调用，
 * 不影响 {@code AttributesUtil.isPhysicalDamage} 的其它调用方。</p>
 */
@Mixin(value = dev.shadowsoffire.attributeslib.impl.AttributeEvents.class, remap = false)
public class MeleeToAllDamageMixin {

    @Redirect(
        method = "meleeDamageAttributes",
        at = @At(
            value = "INVOKE",
            target = "Ldev/shadowsoffire/attributeslib/util/AttributesUtil;isPhysicalDamage(Lnet/minecraft/world/damagesource/DamageSource;)Z"),
        remap = false,
        require = 0)
    private boolean apothiccuriosfix$treatAsPhysical(DamageSource source) {
        if (AttributesUtil.isPhysicalDamage(source)) {
            return true;
        }
        // 3.0.0:旧方案,默认关闭(改由 IndependentAttrDamageHandler 独立结算)
        if (!dev.hengx.apothiccuriosfix.AcfConfig.meleeToAllDamage()) {
            return false;
        }
        if (source == null) {
            return false;
        }
        // 环境伤害(摔落/岩浆/窒息/虚空)没有攻击者,这里天然被排除
        // 必须有一个活体攻击者:施法者 / 召唤者 / 射手 / 近战者
        return source.getEntity() instanceof LivingEntity;
    }
}
