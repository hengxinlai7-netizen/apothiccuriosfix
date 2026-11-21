package dev.hengx.apothiccuriosfix.util;

import dev.hengx.apothiccuriosfix.category.CurioLootCategories;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance;
import dev.shadowsoffire.attributeslib.api.IFormattableAttribute;
import dev.shadowsoffire.attributeslib.api.AttributeHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the replacement attribute block for curio tooltips: <b>{@code 作为饰品佩戴时:} / {@code When worn as a
 * curio:}</b> followed by one line per attribute the item's Apotheosis affixes grant.
 *
 * <p>The numbers used to be printed by ApothicAttributes inside its per-{@code EquipmentSlot} groups ("When in main
 * hand", "When on feet", ...), which are meaningless for a trinket and - worse - printed the same value up to five
 * times. Hiding those groups also hid the values, which is exactly the "still shows nothing" complaint, so the
 * block is now rebuilt here instead: every value once, under one honest heading.</p>
 *
 * <p>{@link #build(ItemStack, Player, TooltipFlag)} is the only place that decides what is shown;
 * {@link #buildStrings(ItemStack, Player)} is the same output flattened to strings, which is what the self test
 * asserts on (a dedicated server cannot render a tooltip, so the strings are the testable contract).</p>
 */
public final class CurioTooltipLines {

    /** Translation key of the heading; provided in {@code assets/apothicurios/lang/*.json}. */
    public static final String HEADER_KEY = "apothiccuriosfix.tooltip.when_worn_as_curio";

    private CurioTooltipLines() {
    }

    /**
     * The complete block for {@code stack}: heading first, then one line per distinct attribute modifier.
     *
     * @return an empty list for anything that is not a curio (armor, weapons, ...), so callers can append
     *         unconditionally
     */
    public static List<Component> build(ItemStack stack, @Nullable Player player, TooltipFlag flag) {
        List<Component> lines = new ArrayList<>();
        if (stack == null || stack.isEmpty() || !isCurio(stack)) {
            return lines;
        }
        Map<String, Component> unique = new LinkedHashMap<>();
        try {
            for (AffixInstance instance : AffixHelper.getAffixes(stack).values()) {
                try {
                    instance.addModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                        String key = key(attribute, modifier);
                        if (key != null && !unique.containsKey(key)) {
                            unique.put(key, format(attribute, modifier, flag));
                        }
                    });
                } catch (RuntimeException | LinkageError ignored) {
                    // one broken affix must not hide the rest
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            return lines;
        }
        if (unique.isEmpty()) {
            return lines;
        }
        lines.add(heading());
        for (Component value : unique.values()) {
            lines.add(value);
        }
        return lines;
    }

    /** Same as {@link #build(ItemStack, Player, TooltipFlag)} but flattened to strings, for tests and logging. */
    public static List<String> buildStrings(ItemStack stack, @Nullable Player player) {
        List<String> out = new ArrayList<>();
        for (Component component : build(stack, player, TooltipFlag.Default.NORMAL)) {
            out.add(component.getString());
        }
        return out;
    }

    /** The translated heading component. */
    public static Component heading() {
        return Component.translatable(HEADER_KEY).withStyle(ChatFormatting.GRAY);
    }

    /** True when the stack is a curio (same rule the tooltip filter uses). */
    public static boolean isCurio(ItemStack stack) {
        return TooltipRules.isCurio(stack);
    }

    /**
     * Whether this mod takes over the attribute block for {@code stack}. Exposed so the self test can assert
     * "curio = true, iron chestplate = false" without a client.
     */
    public static boolean shouldHandle(ItemStack stack) {
        return isCurio(stack);
    }

    /** The block for {@code stack} with the default tooltip flag; empty for non-curios. */
    public static List<Component> lines(ItemStack stack, @Nullable Player player) {
        return build(stack, player, TooltipFlag.Default.NORMAL);
    }

    /**
     * Pushes the block into the same {@link Consumer} ApothicAttributes uses for the tooltip it is building.
     *
     * <p>This is the whole point of 1.20.1h: tooltip replacement mods (Tooltip Overhaul) rebuild the tooltip, but
     * they do not bypass ApothicAttributes' own render path - which is why the vanilla "when in any hand" groups
     * used to be visible. Writing into that consumer means the lines land wherever the pack draws attributes.</p>
     *
     * @return true when the hook handled the stack (the caller then cancels the vanilla block), false when the
     *         item is not a curio and ApothicAttributes must run normally
     */
    public static boolean emit(ItemStack stack, @Nullable Player player, TooltipFlag flag,
                               java.util.function.Consumer<Component> sink) {
        List<Component> block = build(stack, player, flag);
        if (block.isEmpty()) {
            return false;
        }
        try {
            sink.accept(Component.empty());
            for (Component line : block) {
                sink.accept(line);
            }
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
        return true;
    }

    @Nullable
    private static String key(Attribute attribute, AttributeModifier modifier) {
        if (attribute == null || modifier == null) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(attribute);
        return id + "|" + modifier.getOperation() + "|" + modifier.getAmount();
    }

    private static Component format(Attribute attribute, AttributeModifier modifier, TooltipFlag flag) {
        try {
            net.minecraft.network.chat.MutableComponent value =
                    IFormattableAttribute.toComponent(attribute, modifier, flag);
            return AttributeHelper.list().append(value);
        } catch (RuntimeException | LinkageError ignored) {
            return Component.literal(" +" + modifier.getAmount() + " " + attribute.getDescriptionId());
        }
    }

    /** Exposed for the diagnostic command: the attribute ids a curio tooltip block would list. */
    public static List<String> attributeIds(ItemStack stack) {
        List<String> ids = new ArrayList<>();
        try {
            for (AffixInstance instance : AffixHelper.getAffixes(stack).values()) {
                instance.addModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                    ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(attribute);
                    if (id != null) {
                        ids.add(id.toString());
                    }
                });
            }
        } catch (RuntimeException | LinkageError ignored) {
            // diagnostics only
        }
        return ids;
    }

    /** True when the stack resolves to a curio loot category (kept here so callers need one import less). */
    public static boolean isCurioCategory(ItemStack stack) {
        return CurioLootCategories.isCurioStack(stack);
    }
}
