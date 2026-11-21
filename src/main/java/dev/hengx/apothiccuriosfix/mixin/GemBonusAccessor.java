package dev.hengx.apothiccuriosfix.mixin;

import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemClass;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.bonus.GemBonus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 {@link GemBonus} 的 {@code gemClass}(protected):拿到一个 bonus 支持哪些类目,
 * 才能判断它是不是"护甲类"的属性 bonus({@code gem.GemCurioSupport#pick})。
 *
 * <p>用访问器而不是反射:{@code gemClass} 是 {@code protected final} 字段,反射也能读,但访问器
 * 在编译期就能校验字段名,改版 AP 时会直接编译失败而不是运行时静默 null。</p>
 */
@Mixin(value = GemBonus.class, remap = false)
public interface GemBonusAccessor {

    /** 这个 bonus 支持的原版/饰品类目集合。 */
    @Accessor("gemClass")
    GemClass apcfix$gemClass();
}
