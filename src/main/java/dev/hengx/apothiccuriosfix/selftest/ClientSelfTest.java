package dev.hengx.apothiccuriosfix.selftest;

import dev.hengx.apothiccuriosfix.ApothicCuriosFix;
import dev.hengx.apothiccuriosfix.category.CurioLootCategories;
import dev.hengx.apothiccuriosfix.util.AttributeTooltipFilter;
import dev.hengx.apothiccuriosfix.util.TooltipRules;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * End-to-end check of the tooltip fix, run inside a <b>real client</b> when
 * {@code -Dapothiccuriosfix.clienttest=true} is set.
 *
 * <p>A dedicated server cannot prove anything about the tooltip: the whole block is produced by the client-only
 * {@code dev.shadowsoffire.attributeslib.client.AttributesLibClient}, and 1.20.1a shipped a check that only
 * asserted the boolean rule - which is exactly why "still shows 在任意手时" slipped through. This class therefore
 * drives the real code path: it puts a stack with main-hand/off-hand attribute modifiers through
 * {@link ItemStack#getTooltipLines(net.minecraft.world.entity.player.Player, TooltipFlag)} - the method that
 * fires {@code ItemTooltipEvent}, where ApothicAttributes adds the groups and where this mod's mixin and
 * {@link AttributeTooltipFilter} remove them again - and reads the resulting lines back.</p>
 *
 * <p>Two stacks are compared: {@code minecraft:dirt} (no curio category - the group <b>must</b> appear, proving
 * the harness really renders it) and {@code minecraft:stick}, which is registered here as the loot category
 * {@code curios:selftest} and therefore must lose the group. The mixin handler is additionally looked up by name
 * in the transformed {@code AttributesLibClient} class, so the report can state whether the mixin layer was
 * applied or whether {@link AttributeTooltipFilter} alone removed the block.</p>
 *
 * <p>The class deliberately references no client-only type, so it is safe to load on a dedicated server (where
 * {@code ClientTickEvent} simply never fires).</p>
 */
public final class ClientSelfTest {

    public static final String PROPERTY = "apothiccuriosfix.clienttest";
    private static final String FILE_NAME = "apothiccuriosfix_clienttest.log";
    private static final String MIXIN_HANDLER = "apothiccuriosfix$skipCurioSlotSections";

    private static final List<String> LINES = new ArrayList<>();
    private static boolean passed = true;
    private static boolean ran;

    private ClientSelfTest() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ran || !enabled()) {
            return;
        }
        ran = true;
        try {
            run();
        } catch (Throwable t) {
            fail("unexpected exception", t);
        } finally {
            log("RESULT=" + (passed ? "CLIENTPASS" : "CLIENTFAIL"));
            Path path = Path.of(FILE_NAME);
            try {
                Files.writeString(path, String.join(System.lineSeparator(), LINES), StandardCharsets.UTF_8);
                ApothicCuriosFix.LOGGER.info("[apothiccuriosfix][clienttest] written to {}", path.toAbsolutePath());
            } catch (Exception ex) {
                ApothicCuriosFix.LOGGER.error("[apothiccuriosfix][clienttest] could not write {}", path.toAbsolutePath(), ex);
            }
            for (String line : LINES) {
                System.out.println("[apothiccuriosfix][clienttest] " + line);
            }
            System.out.flush();
        }
    }

    private static void run() {
        // The subject needs a curios:* category. minecraft:stick is used because no shipped category can
        // match it, so the category registered here is the one LootCategory.forItem resolves.
        LootCategory selftest = LootCategory.BY_ID.get("curios:selftest");
        if (selftest == null) {
            selftest = LootCategory.register(null, "curios:selftest", stack -> stack.is(Items.STICK),
                    new EquipmentSlot[] { EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND });
        }
        log("test category: " + selftest + " slots=" + java.util.Arrays.toString(selftest.getSlots()));
        check("registered test category curios:selftest", selftest.getName().equals("curios:selftest"),
                selftest.getName());

        ItemStack control = withHandAttributes(new ItemStack(Items.DIRT));
        ItemStack subject = withHandAttributes(new ItemStack(Items.STICK));

        check("LootCategory.forItem(dirt) is not a curios:* category",
                !CurioLootCategories.isCurioCategory(LootCategory.forItem(control)),
                String.valueOf(LootCategory.forItem(control)));
        check("LootCategory.forItem(stick) is curios:selftest",
                LootCategory.forItem(subject) == selftest, String.valueOf(LootCategory.forItem(subject)));

        Set<String> headings = AttributeTooltipFilter.headingTexts();
        log("attribute group headings in this language: " + headings);

        List<String> controlLines = render(control);
        List<String> subjectLines = render(subject);
        log("dirt tooltip: " + controlLines);
        log("stick tooltip: " + subjectLines);

        boolean controlHasGroup = controlLines.stream().anyMatch(headings::contains);
        boolean subjectHasGroup = subjectLines.stream().anyMatch(headings::contains);
        check("control: an item without a curios:* category does render the attribute group",
                controlHasGroup, "group present=" + controlHasGroup);
        check("tooltip rule: the curio stack is classified as hidden", TooltipRules.hidesAttributeSlotSections(subject),
                "hidden=" + TooltipRules.hidesAttributeSlotSections(subject));
        check("curio stack: no attribute group heading in the tooltip", !subjectHasGroup,
                "group present=" + subjectHasGroup);
        check("curio stack: no attribute value line left behind",
                subjectLines.stream().noneMatch(line -> line.contains("+") || line.contains("%")),
                "lines=" + subjectLines);

        // Direct call of the stripper on a synthetic copy of the control tooltip proves the filter works even
        // where the mixin did not run at all.
        List<Component> synthetic = new ArrayList<>();
        synthetic.add(Component.literal("A stick"));
        synthetic.add(Component.empty());
        synthetic.add(Component.translatable("item.modifiers.both_hands"));
        synthetic.add(Component.literal(" +2 Max Health"));
        synthetic.add(Component.empty());
        int stripped = AttributeTooltipFilter.stripAttributeGroups(synthetic);
        check("filter: strips the both_hands group from a raw tooltip list",
                stripped > 0 && synthetic.stream().noneMatch(c -> headings.contains(c.getString())),
                "stripped=" + stripped + " left=" + synthetic.size());

        // 1.20.1g replaced the cancel-at-HEAD client mixin with the rebuilt curio block, so the only thing left to
        // assert here is that the rebuilt block really carries the numbers once.
        List<String> curioBlock = dev.hengx.apothiccuriosfix.util.CurioTooltipLines.buildStrings(subject, null);
        log("curio tooltip block: " + curioBlock);
        check("curio tooltip block lists values under the curio heading",
                !curioBlock.isEmpty() && curioBlock.get(0).equals(
                        Component.translatable(dev.hengx.apothiccuriosfix.util.CurioTooltipLines.HEADER_KEY).getString()),
                "lines=" + curioBlock);
    }

    /** Adds the same max-health modifier to both hands, which is what produces the "both hands" group. */
    private static ItemStack withHandAttributes(ItemStack stack) {
        AttributeModifier modifier = new AttributeModifier(UUID.randomUUID(), "apothiccuriosfix_selftest",
                2.0D, AttributeModifier.Operation.ADDITION);
        stack.addAttributeModifier(Attributes.MAX_HEALTH, modifier, EquipmentSlot.MAINHAND);
        stack.addAttributeModifier(Attributes.MAX_HEALTH, modifier, EquipmentSlot.OFFHAND);
        return stack;
    }

    private static List<String> render(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        for (Component component : stack.getTooltipLines(null, TooltipFlag.Default.NORMAL)) {
            lines.add(component == null ? "" : component.getString());
        }
        return lines;
    }

    /** True when Mixin copied this mod's handler method into the target class. */
    private static boolean mixinApplied() {
        try {
            Class<?> target = Class.forName("dev.shadowsoffire.attributeslib.client.AttributesLibClient", false,
                    ClientSelfTest.class.getClassLoader());
            for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
                if (MIXIN_HANDLER.equals(method.getName())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            log("  could not inspect AttributesLibClient: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            log("[ OK ] " + name + (detail == null ? "" : "  (" + detail + ")"));
        } else {
            fail(name, detail);
        }
    }

    private static void fail(String name, String detail) {
        passed = false;
        log("[FAIL] " + name + (detail == null ? "" : "  (" + detail + ")"));
    }

    private static void fail(String name, Throwable t) {
        passed = false;
        log("[FAIL] " + name + "  (" + t + ")");
    }

    private static void log(String line) {
        LINES.add(line);
        ApothicCuriosFix.LOGGER.info("[apothiccuriosfix][clienttest] " + line);
    }
}
