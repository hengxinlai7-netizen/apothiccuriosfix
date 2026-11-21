package dev.hengx.apothiccuriosfix.util;

/**
 * One-render hand-off between the client mixin and the tooltip filter, so the curio block is added exactly once.
 *
 * <p>{@code AttributesLibClient#applyModifierTooltips} runs first (it builds the per-slot attribute groups), then
 * {@code ItemStack#getTooltipLines} fires {@code ItemTooltipEvent} on the same thread. When the mixin already
 * replaced the attribute block for the stack, the filter must not add a second copy, and when the mixin could not
 * be applied (renamed method, stripped config, ...) the filter keeps working as a fallback.</p>
 */
public final class TooltipHandoff {

    private static final ThreadLocal<Boolean> HANDLED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private TooltipHandoff() {
    }

    /** Called by the mixin right before it cancels the vanilla block. */
    public static void markHandled() {
        HANDLED.set(Boolean.TRUE);
    }

    /** Called by the filter: true when the mixin already produced the block for this render. */
    public static boolean consumeHandled() {
        boolean handled = Boolean.TRUE.equals(HANDLED.get());
        HANDLED.remove();
        return handled;
    }
}
