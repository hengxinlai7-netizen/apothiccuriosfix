package dev.hengx.apothiccuriosfix.event;

import dev.shadowsoffire.attributeslib.api.ALObjects;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 属性伤害词条的<b>独立结算</b>。
 *
 * <p>神化原本把「火属性伤害 / 冰霜伤害」挂在 {@code AttributeEvents.meleeDamageAttributes}
 * （{@code LivingAttackEvent}）里，而那段代码要求 {@code source.getEntity()} 是
 * {@code LivingEntity}。铁魔法的法术伤害拿不到这个值，所以法术永远吃不到属性加成。</p>
 *
 * <p>这里改成<b>玩家造成伤害后自己补一段</b>：只要这次伤害的攻击者是玩家，
 * 就读取他的 {@code fire_damage} / {@code cold_damage} 属性，额外打一段对应类型的伤害。</p>
 *
 * <h2>防重入</h2>
 * <p>额外伤害会再次触发 {@link LivingHurtEvent}，用 {@link ThreadLocal} 标记拦住，
 * 避免「词条触发词条」。</p>
 */
public final class IndependentAttrDamageHandler {

    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> false);

    private IndependentAttrDamageHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHurt(LivingHurtEvent event) {
        if (APPLYING.get()) {
            return;
        }
        try {
            LivingEntity target = event.getEntity();
            if (target == null || target.level().isClientSide || target.isDeadOrDying()) {
                return;
            }
            Entity attacker = event.getSource().getEntity();
            if (!(attacker instanceof LivingEntity)) {
                attacker = event.getSource().getDirectEntity();
            }
            if (!(attacker instanceof LivingEntity living)) {
                return;
            }
            // 只有玩家造成的伤害才补(避免怪物互相打时也吃词条)
            if (!(living instanceof Player)) {
                return;
            }
            float fire = attr(living, ALObjects.Attributes.FIRE_DAMAGE.get());
            float cold = attr(living, ALObjects.Attributes.COLD_DAMAGE.get());
            if (fire <= 0.0F && cold <= 0.0F) {
                return;
            }
            APPLYING.set(true);
            try {
                target.invulnerableTime = 0;
                if (fire > 0.0F) {
                    target.hurt(src(ALObjects.DamageTypes.FIRE_DAMAGE, living), fire);
                }
                if (cold > 0.0F) {
                    target.invulnerableTime = 0;
                    target.hurt(src(ALObjects.DamageTypes.COLD_DAMAGE, living), cold);
                }
            }
            finally {
                APPLYING.set(false);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            APPLYING.set(false);
        }
    }

    private static float attr(LivingEntity entity, Attribute attribute) {
        try {
            if (attribute == null || entity.getAttribute(attribute) == null) {
                return 0.0F;
            }
            return (float) entity.getAttributeValue(attribute);
        }
        catch (RuntimeException | LinkageError ignored) {
            return 0.0F;
        }
    }

    private static DamageSource src(ResourceKey<DamageType> key, LivingEntity attacker) {
        return new DamageSource(
            attacker.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key),
            attacker);
    }
}
