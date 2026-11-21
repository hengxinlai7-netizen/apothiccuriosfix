package dev.hengx.apothiccuriosfix.util;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes the "in any hand / on feet / on legs / on chest / on head" attribute block from curio tooltips.
 *
 * <p>This is the <b>guaranteed</b> half of the tooltip fix, and it needs neither a mixin nor a client-only class:
 * it runs on the finished tooltip line list and deletes the groups {@code AttributesLibClient#applyModifierTooltips}
 * just added, together with their heading line and the separator in front of them. The mixin
 * ({@code mixin.client.AttributesLibClientMixin}) cancels the same block one layer earlier; if that mixin ever
 * fails to apply - a renamed method, a patched ApothicAttributes, a mixin config that a launcher strips - this
 * filter still removes every trace of it, which is exactly what went wrong in 1.20.1a.</p>
 *
 * <p>Only the heading keys ApothicAttributes itself uses are matched, and only on stacks that
 * {@link TooltipRules} classifies as curios, so armor and weapons keep their normal attribute tooltip:</p>
 * <ul>
 *     <li>{@code item.modifiers.both_hands} - the merged MAINHAND/OFFHAND group ("在任意手时");</li>
 *     <li>{@code item.modifiers.mainhand} / {@code .offhand};</li>
 *     <li>{@code item.modifiers.head} / {@code .chest} / {@code .legs} / {@code .feet}.</li>
 * </ul>
 *
 * <p>The comparison uses {@code Component#getString()} against the same translation key, so it is
 * language-independent (the heading is rendered with a gray style, the plain translation resolves to the same
 * text) and works on a dedicated server as well, where the language table is empty and both sides fall back to
 * the raw key.</p>
 */
public final class AttributeTooltipFilter {

    /** Heading keys of every group {@code applyModifierTooltips} can print. */
    private static final String[] HEADING_KEYS = {
            "item.modifiers.both_hands",
            "item.modifiers.mainhand",
            "item.modifiers.offhand",
            "item.modifiers.head",
            "item.modifiers.chest",
            "item.modifiers.legs",
            "item.modifiers.feet"
    };

    private AttributeTooltipFilter() {
    }

    /** Only used by the client self test to prove that the mixin layer works on its own. */
    private static volatile boolean enabled = true;

    /** Test hook: turns this layer off so the mixin layer can be observed in isolation. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!enabled) {
            return;
        }
        try {
            ItemStack stack = event.getItemStack();
            if (stack == null || stack.isEmpty()) {
                return;
            }
            List<Component> tooltip = event.getToolTip();
            if (tooltip == null || tooltip.isEmpty()) {
                return;
            }
            if (!TooltipRules.isCurio(stack)) {
                return; // armor / weapons: not a single line is touched
            }
            if (TooltipHandoff.consumeHandled()) {
                return; // the client mixin already wrote the block into ApothicAttributes' own consumer
            }
            // Fallback used when the client mixin could not be applied: remove ApothicAttributes' per-slot groups
            // and append the rebuilt block to the finished list.
            stripAttributeGroups(tooltip);
            List<Component> block = CurioTooltipLines.build(stack, event.getEntity(), event.getFlags());
            if (!block.isEmpty()) {
                tooltip.add(Component.empty());
                tooltip.addAll(block);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A tooltip must never break the game: on any problem leave it exactly as it is.
        }
    }

    /**
     * Removes every attribute group heading (and the lines that belong to it) from {@code tooltip}.
     *
     * <p>Visible for the self test; mutates the list in place and walks it backwards so indexes stay valid.</p>
     */
    public static int stripAttributeGroups(List<Component> tooltip) {
        Set<String> headings = headingTexts();
        int removed = 0;
        try {
            for (int i = tooltip.size() - 1; i >= 0; i--) {
                String text = tooltip.get(i) == null ? "" : tooltip.get(i).getString();
                if (!headings.contains(text)) {
                    continue;
                }
                int end = i + 1;
                while (end < tooltip.size()) {
                    Component next = tooltip.get(end);
                    if (next == null || next.getString().isEmpty()) {
                        break;
                    }
                    end++;
                }
                removed += end - i;
                tooltip.subList(i, end).clear();
                // applyModifierTooltips emits an empty separator line in front of every group; drop it too.
                if (i - 1 >= 0 && i - 1 < tooltip.size()) {
                    Component before = tooltip.get(i - 1);
                    if (before == null || before.getString().isEmpty()) {
                        tooltip.remove(i - 1);
                        removed++;
                    }
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // Some mod may hand us an immutable tooltip list; the mixin half still covers that case.
        }
        return removed;
    }

    /** The translated heading text for every key, resolved the same way ApothicAttributes resolves it. */
    public static Set<String> headingTexts() {
        Set<String> texts = new LinkedHashSet<>(HEADING_KEYS.length * 2);
        for (String key : HEADING_KEYS) {
            try {
                texts.add(Component.translatable(key).getString());
            } catch (RuntimeException | LinkageError ignored) {
                texts.add(key);
            }
        }
        return texts;
    }
}
