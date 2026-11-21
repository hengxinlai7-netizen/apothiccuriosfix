package dev.hengx.apothiccuriosfix.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.hengx.apothiccuriosfix.category.CurioLootCategories;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.affix.AttributeAffix;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootController;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry;
import dev.shadowsoffire.placebo.reload.DynamicHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code /acf ...} - always-registered, read-only diagnostics for "a curio does not roll numeric (attribute)
 * affixes". No JVM flag, no config, nothing to remember: just type the command in game.
 *
 * <p>Registration prints nothing at all, so the console stays clean (this mod is silent by default). Everything
 * is produced when the command runs: it is sent to the command sender and appended to
 * {@code logs/apothiccuriosfix-diag.log}, so a player can just send that file back.</p>
 *
 * <ul>
 *     <li>{@code /acf diag} - main hand, off hand and every equipped curio, one block each: category, the
 *         category actually registered under that name (plus {@code sameInstance}), rarity, the affixes already
 *         on the item, candidate counts per {@link AffixType}, how many of them are bundled with this mod, and a
 *         per-affix verdict for the numeric ones ({@code canApply=false}, {@code noRarity (has: ...)}), so
 *         "no data" and "filtered out" can be told apart with a single command.</li>
 *     <li>{@code /acf diag item <id>} - the same report for any item id, without holding it.</li>
 *     <li>{@code /acf diag affixes <type>} - first 20 candidate ids of one affix type for the held item.</li>
 *     <li>{@code /acf diag categories} - every {@code curios:*} category with its slots and whether this mod
 *         registered it.</li>
 *     <li>{@code /acf selftest} - rolls one {@code apotheosis:rare} sample per {@code curios:*} category and
 *         reports {@code OK / NO STAT AFFIX / NO AFFIX ROLLED / NO ITEM}: which slots cannot produce numeric
 *         affixes in <em>this</em> pack, without reforging anything by hand.</li>
 * </ul>
 */
public final class DiagCommand {

    public static final String PREFIX = "[apothiccuriosfix][diag] ";
    /** Namespace of the numeric affixes bundled in this mod's jar. */
    public static final String AFFIX_NAMESPACE = "apothiccurios";
    /** Append-only diagnostic log, relative to the game directory. */
    public static final String LOG_PATH = "logs/apothiccuriosfix-diag.log";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_DECISIONS = 10;

    private DiagCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        // No permission requirement on purpose: this is read-only and must work in a normal single player
        // world without any launcher argument.
        dispatcher.register(Commands.literal("acf")
                .then(Commands.literal("diag")
                        .executes(ctx -> diag(ctx.getSource()))
                        .then(Commands.literal("item")
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                        .executes(ctx -> diagItem(ctx.getSource(),
                                                ResourceLocationArgument.getId(ctx, "id")))))
                        .then(Commands.literal("affixes")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .executes(ctx -> listAffixes(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type")))))
                        .then(Commands.literal("categories")
                                .executes(ctx -> listCategories(ctx.getSource()))))
                .then(Commands.literal("roll")
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .then(Commands.argument("rarity", ResourceLocationArgument.id())
                                        .executes(ctx -> rollItem(ctx.getSource(),
                                                ResourceLocationArgument.getId(ctx, "id"),
                                                ResourceLocationArgument.getId(ctx, "rarity"))))))
                .then(Commands.literal("selftest")
                        .executes(ctx -> runSelfTest(ctx.getSource()))));
    }

    // ------------------------------------------------------------------ /acf diag

    private static int diag(CommandSourceStack source) {
        List<String> lines = new ArrayList<>();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            lines.add("no player attached to this command source - use /acf diag item <id> or /acf selftest");
            emit(source, lines);
            return 1;
        }
        lines.add("player " + player.getName().getString() + "  dim=" + player.level().dimension().location());
        addStack(lines, "mainhand", player.getMainHandItem());
        addStack(lines, "offhand", player.getOffhandItem());
        try {
            ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
            if (handler != null) {
                for (Map.Entry<String, ICurioStacksHandler> entry : handler.getCurios().entrySet()) {
                    IDynamicStackHandler stacks = entry.getValue().getStacks();
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        addStack(lines, "curio:" + entry.getKey() + "[" + i + "]", stacks.getStackInSlot(i));
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            lines.add("curio inventory not readable: " + ex);
        }
        LootCategory charm = LootCategory.BY_ID.get("curios:charm");
        lines.add("audit curios:charm  by_id=" + describeCategory(charm));
        ItemStack probe = player.getMainHandItem();
        if (!probe.isEmpty()) {
            LootCategory resolved = LootCategory.forItem(probe);
            lines.add("audit curios:charm  forItem(" + id(probe) + ")=" + describeCategory(resolved)
                    + "  sameInstance=" + (resolved == charm)
                    + "  sameName=" + (charm != null && resolved != null && charm.getName().equals(resolved.getName())));
        }
        lines.add("curios categories: " + CurioLootCategories.categories().size() + " tracked, "
                + countCurioCategories() + " in LootCategory.BY_ID");
        emit(source, lines);
        return 1;
    }

    private static int diagItem(CommandSourceStack source, ResourceLocation id) {
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            List<String> lines = new ArrayList<>();
            lines.add("unknown item id '" + id + "'");
            emit(source, lines);
            return 1;
        }
        List<String> lines = new ArrayList<>();
        try {
            addStack(lines, "item " + id, new ItemStack(item));
        } catch (Throwable t) {
            lines.add("dump failed: " + t);
        }
        emit(source, lines);
        return 1;
    }

    private static int listAffixes(CommandSourceStack source, String typeName) {
        List<String> lines = new ArrayList<>();
        ServerPlayer player = source.getPlayer();
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getMainHandItem();
        if (stack.isEmpty()) {
            lines.add("hold the item in your main hand, or use /acf diag item <id>");
            emit(source, lines);
            return 1;
        }
        AffixType type = parseType(typeName);
        if (type == null) {
            lines.add("unknown affix type '" + typeName + "' (try " + java.util.Arrays.toString(AffixType.values()) + ")");
            emit(source, lines);
            return 1;
        }
        LootCategory category = LootCategory.forItem(stack);
        LootRarity rarity = rarityOrFallback(stack);
        List<ResourceLocation> candidates = candidates(stack, category, rarity, type);
        lines.add("affixes type=" + type + "  item=" + id(stack) + "  category=" + describeCategory(category)
                + "  rarity=" + rarityId(rarity) + "  candidates=" + candidates.size());
        int shown = 0;
        for (ResourceLocation candidate : candidates) {
            if (shown++ >= 20) {
                lines.add("  ... " + (candidates.size() - 20) + " more");
                break;
            }
            lines.add("  " + candidate + (isOurAffix(candidate) ? "   <== bundled by this mod" : ""));
        }
        if (candidates.isEmpty()) {
            lines.add("  (no affix of this type can apply - the data for this category/rarity is missing)");
        }
        emit(source, lines);
        return 1;
    }

    private static int listCategories(CommandSourceStack source) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, LootCategory> entry : LootCategory.BY_ID.entrySet()) {
            if (!entry.getKey().startsWith(CurioLootCategories.PREFIX)) {
                continue;
            }
            LootCategory category = entry.getValue();
            lines.add(String.format(Locale.ROOT, "%-26s slots=%-22s tracked=%s", entry.getKey(),
                    java.util.Arrays.toString(category.getSlots()),
                    CurioLootCategories.categories().containsValue(category)));
        }
        lines.add("total curios:* categories: " + countCurioCategories());
        emit(source, lines);
        return 1;
    }

    // ------------------------------------------------------------------ /acf selftest

    private static int runSelfTest(CommandSourceStack source) {
        List<String> lines = new ArrayList<>();
        lines.add("selftest: affixes in registry=" + AffixRegistry.INSTANCE.getKeys().size()
                + "  curios categories=" + countCurioCategories());
        tooltipChecks(lines);
        Pass rare = rollPass(lines, "apotheosis:rare", false);
        Pass ancient = rollPass(lines, "apotheosis:ancient", true);
        Pass esoteric = rollPass(lines, "apotheotic_additions:esoteric", true);
        lines.add("summary rare    : " + rare);
        lines.add("summary ancient : " + ancient);
        lines.add("summary esoteric: " + esoteric);
        boolean ok = rare.clean() && ancient.clean() && esoteric.clean();
        if (ancient.clean()) {
            lines.add("ancient note: the ANCIENT loot rule (no affix of that type exists in 7.4.8) was fulfilled"
                    + " through the STAT fallback and logged no error");
        }
        if (esoteric.skipped) {
            lines.add("esoteric note: rarity not loaded in this pack - skipped"
                    + " (apotheotic_additions is not installed here)");
        } else if (esoteric.clean()) {
            lines.add("esoteric note: a rarity whose tier is missing from every affix values block was rolled"
                    + " through the higher-tier fallback and logged no error");
        }
        lines.add("RESULT=" + (ok ? "PASS" : "CHECK"));
        emit(source, lines);
        return 1;
    }

    /**
     * The tooltip contract, asserted as pure strings (a dedicated server cannot render a tooltip): a curio must list
     * its numeric values once under the curio heading and must not contain ApothicAttributes' per-slot headings,
     * while armor must keep exactly what it had.
     */
    private static void tooltipChecks(List<String> lines) {
        lines.add("[tooltip] " + dev.hengx.apothiccuriosfix.util.CurioTooltipLines.HEADER_KEY + " = "
                + net.minecraft.network.chat.Component.translatable(
                        dev.hengx.apothiccuriosfix.util.CurioTooltipLines.HEADER_KEY).getString());
        LootRarity ancient = RarityRegistry.INSTANCE.getValue(new ResourceLocation("apotheosis:ancient"));
        ItemStack charm = findItem("charm", LootCategory.BY_ID.get("curios:charm"));
        if (charm.isEmpty() || ancient == null) {
            lines.add("[tooltip] SKIP (no charm sample item or no ancient rarity)");
            return;
        }
        ItemStack rolled = LootController.createLootItem(charm.copy(), ancient, RandomSource.create(0x5EEDL));
        List<String> block = dev.hengx.apothiccuriosfix.util.CurioTooltipLines.buildStrings(rolled, null);
        List<String> headings = new ArrayList<>(dev.hengx.apothiccuriosfix.util.AttributeTooltipFilter.headingTexts());
        boolean hasHeader = !block.isEmpty() && net.minecraft.network.chat.Component
                .translatable(dev.hengx.apothiccuriosfix.util.CurioTooltipLines.HEADER_KEY).getString()
                .equals(block.get(0));
        boolean hasNumber = block.stream().anyMatch(line -> line.contains("+") || line.contains("%"));
        boolean hasSlotHeading = block.stream().anyMatch(headings::contains);
        boolean unique = block.size() - 1 == block.subList(1, block.size()).stream().distinct().count();
        lines.add("[tooltip] curio block(" + (block.size() - 1) + " value lines): " + block);
        lines.add("[tooltip] curio: header=" + hasHeader + " numbers=" + hasNumber
                + " slotHeadings=" + hasSlotHeading + " uniqueValues=" + unique);

        ItemStack chestplate = new ItemStack(Items.IRON_CHESTPLATE);
        List<String> chestBlock = dev.hengx.apothiccuriosfix.util.CurioTooltipLines.buildStrings(chestplate, null);
        // A non-curio stack must not receive the curio block, and the filter bails out before stripping anything,
        // so the vanilla "when on chest" group of a chestplate is still rendered by ApothicAttributes.
        boolean chestIsCurio = dev.hengx.apothiccuriosfix.util.TooltipRules.isCurio(chestplate);
        boolean vanillaChestHeadingExists = headings.contains(
                net.minecraft.network.chat.Component.translatable("item.modifiers.chest").getString());
        lines.add("[tooltip] chestplate: curioBlock=" + chestBlock.size() + " isCurio=" + chestIsCurio
                + " vanillaChestHeadingExists=" + vanillaChestHeadingExists + " (0/false/true expected)");
        // the two things the client mixin uses: shouldHandle(stack) and the lines it pushes into the consumer
        boolean charmHandled = dev.hengx.apothiccuriosfix.util.CurioTooltipLines.shouldHandle(rolled);
        List<net.minecraft.network.chat.Component> sink = new ArrayList<>();
        boolean emitted = dev.hengx.apothiccuriosfix.util.CurioTooltipLines.emit(rolled, null,
                net.minecraft.world.item.TooltipFlag.Default.NORMAL, sink::add);
        List<String> emittedText = new ArrayList<>();
        for (net.minecraft.network.chat.Component component : sink) {
            emittedText.add(component.getString());
        }
        lines.add("[tooltip] hook: shouldHandle(curio)=" + charmHandled + " shouldHandle(chestplate)="
                + dev.hengx.apothiccuriosfix.util.CurioTooltipLines.shouldHandle(chestplate)
                + " emit=" + emitted + " consumerLines=" + emittedText.size());
        lines.add("[tooltip] consumer: " + emittedText);
        boolean hookOk = charmHandled
                && !dev.hengx.apothiccuriosfix.util.CurioTooltipLines.shouldHandle(chestplate)
                && emitted
                && emittedText.size() == block.size() + 1; // one leading empty line + the block
        lines.add("[tooltip] RESULT=" + ((hasHeader && hasNumber && !hasSlotHeading && unique
                && chestBlock.isEmpty() && !chestIsCurio && vanillaChestHeadingExists && hookOk) ? "PASS" : "CHECK"));
    }
    /** One table of {@code createLootItem} rolls at one rarity, one row per {@code curios:*} category. */
    private static Pass rollPass(List<String> lines, String rarityId, boolean strict) {
        LootRarity rarity = RarityRegistry.INSTANCE.getValue(new ResourceLocation(rarityId));
        Pass pass = new Pass(rarityId);
        if (rarity == null) {
            lines.add("[" + rarityId + "] rarity not loaded - skipped");
            pass.skipped = true;
            return pass;
        }
        lines.add("[" + rarityId + "] rules=" + describeRules(rarity));
        int statRules = 0;
        for (LootRarity.LootRule rule : rarity.getRules()) {
            if (rule.type() == AffixType.STAT || rule.type() == AffixType.ANCIENT) {
                statRules++;
            }
        }
        pass.minAffixes = statRules;
        lines.add("[" + rarityId + "] rules that must add an affix (STAT/ANCIENT at any chance): " + statRules);
        for (Map.Entry<String, LootCategory> entry : new ArrayList<>(LootCategory.BY_ID.entrySet())) {
            String categoryId = entry.getKey();
            if (!categoryId.startsWith(CurioLootCategories.PREFIX)) {
                continue;
            }
            String slot = categoryId.substring(CurioLootCategories.PREFIX.length());
            LootCategory category = entry.getValue();
            ItemStack sample = findItem(slot, category);
            if (sample.isEmpty()) {
                pass.noItem++;
                lines.add(String.format(Locale.ROOT, "%-10s %-22s %-30s %-46s %s", rarityId, categoryId, "-",
                        "NO ITEM for this slot", "NO ITEM"));
                continue;
            }
            int statCandidates = candidates(sample, category, rarity, AffixType.STAT).size();
            List<String> ids = new ArrayList<>();
            int stat = 0;
            int our = 0;
            String state;
            try {
                ItemStack rolled = LootController.createLootItem(sample.copy(), rarity,
                        RandomSource.create(0x5EEDL + slot.hashCode()));
                for (Map.Entry<DynamicHolder<? extends Affix>, AffixInstance> affix
                        : AffixHelper.getAffixes(rolled).entrySet()) {
                    Affix value = affix.getKey().getOptional().orElse(null);
                    boolean isStat = value instanceof AttributeAffix
                            || (value != null && value.getType() == AffixType.STAT);
                    if (isStat) {
                        stat++;
                    }
                    if (isOurAffix(affix.getKey().getId())) {
                        our++;
                    }
                    ids.add(affix.getKey().getId() + (isStat ? "*" : ""));
                }
                if (ids.isEmpty()) {
                    state = "NO AFFIX ROLLED";
                } else if (stat == 0) {
                    state = "NO STAT AFFIX";
                } else if (strict && our == 0) {
                    state = "NO MOD AFFIX";
                } else if (ids.size() < statRules) {
                    state = "TOO FEW AFFIXES";
                } else {
                    state = "OK";
                }
            } catch (Throwable t) {
                state = "EXCEPTION: " + t;
                ids.add(String.valueOf(t));
            }
            if ("OK".equals(state)) {
                pass.ok++;
            } else {
                pass.problems.add(categoryId + " (" + state + ")");
            }
            lines.add(String.format(Locale.ROOT, "%-10s %-22s %-30s statCand=%-3d stat=%-2d our=%-2d n=%-2d %-56s %s",
                    rarityId, categoryId, id(sample), statCandidates, stat, our, ids.size(), ids, state));
        }
        return pass;
    }

    /** How one rarity pass went. */
    private static final class Pass {
        final String rarityId;
        int ok;
        int noItem;
        int minAffixes;
        boolean skipped;
        final List<String> problems = new ArrayList<>();

        Pass(String rarityId) {
            this.rarityId = rarityId;
        }

        boolean clean() {
            return !skipped && problems.isEmpty();
        }

        @Override
        public String toString() {
            if (skipped) {
                return rarityId + " SKIPPED (rarity not loaded)";
            }
            return rarityId + " OK=" + ok + " NO_ITEM=" + noItem + " problems=" + problems.size()
                    + (problems.isEmpty() ? "" : " " + problems);
        }
    }
    /** First item that belongs to {@code curios:<slot>}: item tag, then id match, then category match. */
    private static ItemStack findItem(String slot, LootCategory category) {
        TagKey<Item> tag = TagKey.create(Registries.ITEM, new ResourceLocation("curios", slot));
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ItemStack stack = new ItemStack(item);
            try {
                if (stack.is(tag)) {
                    return stack;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // a slot without tags is fine, keep looking
            }
        }
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ItemStack stack = new ItemStack(item);
            if (CurioLootCategories.matchesSlotId(stack, slot)) {
                return stack;
            }
        }
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ItemStack stack = new ItemStack(item);
            if (LootCategory.forItem(stack) == category) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ /acf roll <id> <rarity>

    private static int rollItem(CommandSourceStack source, ResourceLocation itemId, ResourceLocation rarityId) {
        List<String> lines = new ArrayList<>();
        Item item = itemId == null ? null : ForgeRegistries.ITEMS.getValue(itemId);
        LootRarity rarity = rarityId == null ? null : RarityRegistry.INSTANCE.getValue(rarityId);
        if (item == null || rarity == null) {
            lines.add("unknown item or rarity: item=" + itemId + " rarity=" + rarityId);
            emit(source, lines);
            return 1;
        }
        ItemStack stack = new ItemStack(item);
        lines.add("roll " + itemId + " at " + rarityId + "  rules=" + describeRules(rarity));
        lines.add("  candidates before roll: STAT=" + candidates(stack, LootCategory.forItem(stack), rarity, AffixType.STAT).size());
        try {
            ItemStack rolled = LootController.createLootItem(stack.copy(), rarity, RandomSource.create(0x5EEDL));
            Map<DynamicHolder<? extends Affix>, AffixInstance> affixes = AffixHelper.getAffixes(rolled);
            List<String> ids = new ArrayList<>();
            int stat = 0;
            int our = 0;
            for (Map.Entry<DynamicHolder<? extends Affix>, AffixInstance> entry : affixes.entrySet()) {
                Affix value = entry.getKey().getOptional().orElse(null);
                boolean isStat = value instanceof AttributeAffix || (value != null && value.getType() == AffixType.STAT);
                if (isStat) {
                    stat++;
                }
                if (isOurAffix(entry.getKey().getId())) {
                    our++;
                }
                ids.add(entry.getKey().getId() + (isStat ? "*" : ""));
            }
            lines.add("  affixes(" + ids.size() + "): " + ids);
            lines.add("  statAffixes=" + stat + "  ourAffixes=" + our
                    + "  RESULT=" + (stat > 0 ? "HAS_STAT" : "NO_STAT"));
        } catch (Throwable t) {
            lines.add("  roll FAILED: " + t);
            lines.add("  RESULT=EXCEPTION");
        }
        emit(source, lines);
        return 1;
    }

    /** "{ANCIENT:1.0, STAT:1.0, ...}" for a rarity, so the user sees what a roll is supposed to produce. */
    public static String describeRules(@Nullable LootRarity rarity) {
        if (rarity == null) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        try {
            for (LootRarity.LootRule rule : rarity.getRules()) {
                StringBuilder part = new StringBuilder();
                part.append(rule.type()).append(':').append(rule.chance());
                if (rule.backup() != null) {
                    part.append("->").append(rule.backup().type()).append(':').append(rule.backup().chance());
                }
                parts.add(part.toString());
            }
        } catch (RuntimeException | LinkageError ex) {
            return "unreadable (" + ex + ")";
        }
        return parts.toString();
    }

    // ------------------------------------------------------------------ per-stack report

    /** Appends the full report for one stack (label used as the section header). */
    public static void addStack(List<String> lines, String label, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        lines.add("--- " + label + ": " + id(stack) + " x" + stack.getCount());
        LootCategory category = LootCategory.forItem(stack);
        LootCategory byId = category == null ? null : LootCategory.BY_ID.get(category.getName());
        lines.add("  category   : " + describeCategory(category));
        lines.add("  by_id      : " + describeCategory(byId) + "  sameInstance=" + (byId == category));
        DynamicHolder<LootRarity> rarityHolder = AffixHelper.getRarity(stack);
        LootRarity rarity = rarityHolder != null && rarityHolder.isBound() ? rarityHolder.get() : null;
        boolean rarityAssumed = false;
        if (rarity == null) {
            rarity = rarityOrFallback(stack);
            rarityAssumed = true;
        }
        lines.add("  rarity     : " + (rarityHolder == null ? "none" : rarityHolder.getId()
                + " bound=" + rarityHolder.isBound() + " resolved=" + rarityId(rarity))
                + (rarityAssumed ? "   (item carries no rarity - counting with " + rarityId(rarity) + ")" : ""));
        lines.add("  rules      : " + describeRules(rarity));
        Map<DynamicHolder<? extends Affix>, AffixInstance> affixes = AffixHelper.getAffixes(stack);
        List<String> affixIds = new ArrayList<>();
        for (DynamicHolder<? extends Affix> holder : affixes.keySet()) {
            affixIds.add(String.valueOf(holder.getId()));
        }
        lines.add("  affixes(" + affixIds.size() + "): " + affixIds);
        if (category == null || rarity == null) {
            lines.add("  candidates : n/a (no category or no rarity)");
            return;
        }
        StringBuilder counts = new StringBuilder();
        int total = 0;
        int our = 0;
        for (AffixType type : AffixType.values()) {
            List<ResourceLocation> list = candidates(stack, category, rarity, type);
            total += list.size();
            for (ResourceLocation candidate : list) {
                if (isOurAffix(candidate)) {
                    our++;
                }
            }
            if (type == AffixType.STAT || type == AffixType.POTION || type == AffixType.ABILITY
                    || type == AffixType.ANCIENT) {
                counts.append(type).append('=').append(list.size()).append(' ');
            }
        }
        lines.add("  candidates : " + counts.toString().trim() + "  total=" + total + "  ourAffixes=" + our);
        addStatDecisions(lines, stack, category, rarity);
    }

    /**
     * The "why did nothing numeric roll" section: a per-affix verdict for the numeric ({@link AffixType#STAT})
     * affixes of the registry, rejected ones first, at most {@value #MAX_DECISIONS} of them.
     *
     * <p>Two rejections are possible and they need different fixes:</p>
     * <ul>
     *     <li>{@code canApply=false} with {@code noRarity (has: ...)} - the affix knows this category but its
     *         {@code values} block has no entry for the rarity of the item (incomplete data);</li>
     *     <li>{@code canApply=false} with {@code not-for-this-category} - the affix does not declare this
     *         category at all (wrong {@code types}, or the file was parsed for another slot).</li>
     * </ul>
     */
    public static void addStatDecisions(List<String> lines, ItemStack stack, LootCategory category, LootRarity rarity) {
        List<Affix> statAffixes = new ArrayList<>();
        try {
            for (Affix affix : AffixRegistry.INSTANCE.getValues()) {
                if (affix != null && affix.getType() == AffixType.STAT) {
                    statAffixes.add(affix);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            lines.add("  stat check : unavailable (" + ex + ")");
            return;
        }
        lines.add("  stat check : " + statAffixes.size()
                + " numeric affix(es) in the registry, verdicts for this item/category/rarity:");
        if (statAffixes.isEmpty()) {
            lines.add("    (the registry holds no numeric affix at all - the data never parsed)");
            return;
        }
        // Verdict per affix, then show the informative ones first:
        //   0 = declares this category but lacks this rarity ("noRarity")  <- the data problem to look for
        //   1 = fully applicable ("canApply=true")
        //   2 = belongs to other categories ("not-for-this-category")     <- pure noise for this item
        List<Verdict> verdicts = new ArrayList<>();
        for (Affix affix : statAffixes) {
            ResourceLocation key = AffixRegistry.INSTANCE.getKey(affix);
            boolean applies;
            try {
                applies = affix.canApplyTo(stack, category, rarity);
            } catch (RuntimeException | LinkageError ex) {
                verdicts.add(new Verdict(key, 3, "canApply=ERROR (" + ex + ")", List.of()));
                continue;
            }
            if (applies) {
                verdicts.add(new Verdict(key, 1, "canApply=true   rarity=" + rarityId(rarity), List.of()));
                continue;
            }
            List<String> hasRarities = new ArrayList<>();
            for (ResourceLocation rarityKey : RarityRegistry.INSTANCE.getKeys()) {
                LootRarity other = RarityRegistry.INSTANCE.getValue(rarityKey);
                if (other == null) {
                    continue;
                }
                try {
                    if (affix.canApplyTo(stack, category, other)) {
                        hasRarities.add(rarityKey.getPath());
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // ignore a single broken affix
                }
            }
            if (hasRarities.isEmpty()) {
                verdicts.add(new Verdict(key, 2, "canApply=false  not-for-this-category (" + category.getName() + ")",
                        List.of()));
            } else {
                verdicts.add(new Verdict(key, 0,
                        "canApply=false  noRarity (has: " + String.join(",", hasRarities) + ")", hasRarities));
            }
        }
        verdicts.sort(Comparator.comparingInt((Verdict verdict) -> verdict.rank)
                .thenComparing(verdict -> String.valueOf(verdict.id)));
        int shown = 0;
        for (Verdict verdict : verdicts) {
            if (shown >= MAX_DECISIONS) {
                lines.add("    ... " + (verdicts.size() - shown) + " more numeric affix(es)");
                break;
            }
            shown++;
            lines.add("    " + verdict.id + "  " + verdict.text);
        }
    }

    /** One line of the per-affix verdict, with the sort rank described above. */
    private record Verdict(ResourceLocation id, int rank, String text, List<String> rarities) {
    }

    // ------------------------------------------------------------------ helpers

    /** Every affix of {@code type} that can apply to this stack/category/rarity, sorted by id. */
    public static List<ResourceLocation> candidates(ItemStack stack, LootCategory category, LootRarity rarity,
                                                    AffixType type) {
        List<ResourceLocation> found = new ArrayList<>();
        if (category == null || rarity == null) {
            return found;
        }
        try {
            for (Affix affix : AffixRegistry.INSTANCE.getValues()) {
                if (affix == null || affix.getType() != type) {
                    continue;
                }
                if (!affix.canApplyTo(stack, category, rarity)) {
                    continue;
                }
                ResourceLocation key = AffixRegistry.INSTANCE.getKey(affix);
                if (key != null) {
                    found.add(key);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // a broken affix must not break the report
        }
        found.sort(Comparator.comparing(ResourceLocation::toString));
        return found;
    }

    /** True when the affix is one of the {@code apothiccurios:*_plus} files bundled in this jar. */
    public static boolean isOurAffix(@Nullable ResourceLocation id) {
        return id != null && AFFIX_NAMESPACE.equals(id.getNamespace()) && id.getPath().endsWith("_plus");
    }

    /** The item's rarity, or {@code apotheosis:rare} when the item carries none. */
    @Nullable
    public static LootRarity rarityOrFallback(ItemStack stack) {
        DynamicHolder<LootRarity> holder = AffixHelper.getRarity(stack);
        LootRarity rarity = holder != null && holder.isBound() ? holder.get() : null;
        if (rarity != null) {
            return rarity;
        }
        LootRarity fallback = RarityRegistry.INSTANCE.getValue(new ResourceLocation("apotheosis:rare"));
        if (fallback != null) {
            return fallback;
        }
        for (ResourceLocation key : RarityRegistry.INSTANCE.getKeys()) {
            LootRarity value = RarityRegistry.INSTANCE.getValue(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public static String id(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? String.valueOf(stack.getItem()) : key.toString();
    }

    public static String describeCategory(@Nullable LootCategory category) {
        if (category == null) {
            return "null";
        }
        return category.getName() + " slots=" + java.util.Arrays.toString(category.getSlots());
    }

    public static String rarityId(@Nullable LootRarity rarity) {
        if (rarity == null) {
            return "none";
        }
        ResourceLocation key = RarityRegistry.INSTANCE.getKey(rarity);
        return key == null ? String.valueOf(rarity) : key.toString();
    }

    @Nullable
    public static AffixType parseType(String name) {
        for (AffixType type : AffixType.values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    public static int countCurioCategories() {
        int count = 0;
        for (String key : LootCategory.BY_ID.keySet()) {
            if (key.startsWith(CurioLootCategories.PREFIX)) {
                count++;
            }
        }
        return count;
    }

    /** Every curio slot that currently has a category. */
    public static Set<String> curioCategories() {
        Set<String> slots = new LinkedHashSet<>();
        for (String key : LootCategory.BY_ID.keySet()) {
            if (key.startsWith(CurioLootCategories.PREFIX)) {
                slots.add(key.substring(CurioLootCategories.PREFIX.length()));
            }
        }
        return slots;
    }

    /**
     * Sends the lines to the command sender and appends them to {@code logs/apothiccuriosfix-diag.log}.
     * Nothing goes to the normal log, so running a command never spams the console.
     */
    public static void emit(CommandSourceStack source, List<String> lines) {
        StringBuilder file = new StringBuilder();
        file.append("=== ").append(LocalDateTime.now().format(STAMP)).append("  ")
                .append(source.getTextName()).append(System.lineSeparator());
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(PREFIX + line), false);
            file.append(PREFIX).append(line).append(System.lineSeparator());
        }
        appendDiagLog(file.toString());
    }

    /** Appends to the diagnostic log; never throws. */
    public static void appendDiagLog(String text) {
        try {
            Path path = Path.of(LOG_PATH);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // diagnostics must never break the game
        }
    }
}
