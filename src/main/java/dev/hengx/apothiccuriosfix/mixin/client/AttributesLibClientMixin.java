package dev.hengx.apothiccuriosfix.mixin.client;

import dev.hengx.apothiccuriosfix.util.CurioTooltipLines;
import dev.hengx.apothiccuriosfix.util.TooltipHandoff;
import dev.shadowsoffire.attributeslib.client.AttributesLibClient;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Replaces the per-{@code EquipmentSlot} attribute block of a curio with the "worn as a curio" block - <b>on the
 * path ApothicAttributes itself renders through</b>.
 *
 * <p>1.20.1g wrote the replacement block from an {@code ItemTooltipEvent} handler. That is too late for packs that
 * replace the tooltip wholesale: Tooltip Overhaul rebuilds the tooltip, so lines appended to the finished list are
 * not necessarily drawn. What <em>is</em> drawn in those packs is this method - the player could see its
 * "在任意手时 / 穿在脚下时" groups. So the block is written into the very {@link Consumer} this method would have
 * used, and the vanilla groups are cancelled:</p>
 *
 * <pre>
 * 作为饰品佩戴时：
 *   ┇ +23 最大生命值
 *   ┇ +23% 移动速度
 * </pre>
 *
 * <p>Non-curio stacks take the normal path untouched (armor and weapons keep "穿在身上时" and friends).
 * {@code require = 0} plus a config-level {@code "required": false} keep a future ApothicAttributes rename from
 * crashing the pack; in that case {@code AttributeTooltipFilter} still strips the vanilla groups and appends the
 * block from {@code ItemTooltipEvent} (see {@link TooltipHandoff}).</p>
 */
@Mixin(value = AttributesLibClient.class, remap = false)
public class AttributesLibClientMixin {

    @Inject(method = "applyModifierTooltips", at = @At("HEAD"), cancellable = true, require = 0)
    private static void apothiccuriosfix$curioBlock(Player player, ItemStack stack,
                                                    Consumer<Component> tooltips, TooltipFlag flag,
                                                    CallbackInfo ci) {
        try {
            if (CurioTooltipLines.emit(stack, player, flag == null ? TooltipFlag.Default.NORMAL : flag, tooltips)) {
                TooltipHandoff.markHandled();
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // a tooltip must never break: fall through to ApothicAttributes' own block
        }
    }
}
