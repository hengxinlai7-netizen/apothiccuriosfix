package dev.hengx.apothiccuriosfix.mixin;

import dev.hengx.apothiccuriosfix.gem.GemCurioSupport;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.Gem;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.bonus.GemBonus;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/**
 * 让白名单里的**原有**宝石能镶到饰品上({@code GemCurioSupport} 里有挑选规则和名单)。
 *
 * <p>只补两条回退,AP 原生行为一个字节都不改:</p>
 * <ul>
 *     <li>{@code isValidIn(gem, item, rarity)} 返回 false 时,若类目是 {@code curios:<slot>} 且宝石在白名单
 *         ⇒ 改成 true。这就是"能不能镶"的唯一判据(SocketingRecipe → GemInstance.isValid →
 *         Gem.canApplyTo → isValidIn);</li>
 *     <li>{@code getBonus(category, rarity)} 返回 empty 时,若是饰品类目且在白名单
 *         ⇒ 返回 {@link GemCurioSupport#pick} 挑出来的那个 bonus。{@code GemInstance.isValid()} 和
 *         {@code GemInstance.addModifiers(...)} 都走这个方法,所以"镶进去但没属性"不会发生。</li>
 * </ul>
 *
 * <p>{@code remap = false}:两个方法都是 Apotheosis 自己的方法,不是原版方法,运行期名字不变。</p>
 */
@Mixin(value = Gem.class, remap = false)
public abstract class GemMixin {

    /** 宝石自己的 bonus 列表(AP 里是 {@code protected final List<GemBonus> bonuses})。 */
    @Shadow
    protected List<GemBonus> bonuses;

    /**
     * 参数顺序(after javap of {@code GemInstance#canApplyTo} → {@code Gem#canApplyTo}):
     * <b>第 1 个是装备,第 2 个才是宝石</b>。第一版把它写反了,于是 {@code forItem} 看的是宝石自己,
     * 永远不是 curios 类目 —— 表现出来就是"能加孔、但镶不进宝石"。
     */
    @Inject(method = "isValidIn", at = @At("RETURN"), cancellable = true, remap = false)
    private void apcfix$allowOnCurio(ItemStack itemStack, ItemStack gemStack, LootRarity rarity,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        try {
            if (!GemCurioSupport.isAllowed((Gem) (Object) this)) {
                return;
            }
            if (!GemCurioSupport.isCurioCategory(LootCategory.forItem(itemStack))) {
                return;
            }
            if (GemCurioSupport.pick((Gem) (Object) this, this.bonuses, rarity) != null) {
                cir.setReturnValue(true);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 任何意外都退回 AP 原生结论(false),绝不让别的模组的判定崩掉
        }
    }

    @Inject(method = "getBonus", at = @At("RETURN"), cancellable = true, remap = false)
    private void apcfix$curioBonus(LootCategory category, LootRarity rarity,
                                   CallbackInfoReturnable<Optional<GemBonus>> cir) {
        Optional<GemBonus> current = cir.getReturnValue();
        if (current != null && current.isPresent()) {
            return;
        }
        try {
            if (!GemCurioSupport.isCurioCategory(category)) {
                return;
            }
            if (!GemCurioSupport.isAllowed((Gem) (Object) this)) {
                return;
            }
            GemBonus bonus = GemCurioSupport.pick((Gem) (Object) this, this.bonuses, rarity);
            if (bonus != null) {
                cir.setReturnValue(Optional.of(bonus));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 同上:失败就保持 empty
        }
    }
}
