package dev.hengx.apothiccuriosfix.mixin;

import dev.shadowsoffire.apotheosis.adventure.socket.gem.bonus.AttributeBonus;
import net.minecraft.world.entity.ai.attributes.Attribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 {@link AttributeBonus} 的 {@code attribute}(protected)。
 *
 * <p>用来判断一条 bonus 到底加的是哪个属性 —— 增伤类宝石(武士/战神/阳炎/月华/嗜血领主……)同时还有
 * 移速、生命这类"防御/功能"bonus,不读属性名就只能按顺序瞎猜,会把「武士宝石」变成移速宝石。</p>
 */
@Mixin(value = AttributeBonus.class, remap = false)
public interface AttributeBonusAccessor {

    /** 这条 bonus 加成的属性。 */
    @Accessor("attribute")
    Attribute apcfix$attribute();
}
