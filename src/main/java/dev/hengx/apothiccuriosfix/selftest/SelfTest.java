package dev.hengx.apothiccuriosfix.selftest;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.hengx.apothiccuriosfix.ApothicCuriosFix;
import dev.hengx.apothiccuriosfix.category.CurioLootCategories;
import dev.hengx.apothiccuriosfix.event.CurioAffixAttributeBridge;
import dev.hengx.apothiccuriosfix.gem.GemCurioSupport;
import dev.hengx.apothiccuriosfix.util.AttributeTooltipFilter;
import dev.hengx.apothiccuriosfix.util.TooltipRules;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry;
import dev.shadowsoffire.apotheosis.adventure.affix.AttributeAffix;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootController;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.Gem;
import dev.shadowsoffire.apotheosis.adventure.socket.SocketHelper;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemItem;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemRegistry;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.bonus.GemBonus;
import dev.shadowsoffire.placebo.reload.DynamicHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Self check that runs inside the mod class loader of a real dedicated server when
 * {@code -Dapothiccuriosfix.selftest=true} is set. It halts the server when done and writes
 * {@code apothiccuriosfix_selftest.log} next to the server jar, so the result can be collected without
 * scrolling through the whole log.
 *
 * <p>Checks that need a data pack (an item in the {@code curios:charm} tag, an affix whose {@code types}
 * contains {@code curios:charm}) are reported as SKIP when the pack is absent, so a bare run still ends in
 * PASS while the full run proves the whole pipeline.</p>
 */
public final class SelfTest {

    public static final String PROPERTY = "apothiccuriosfix.selftest";
    private static final String FILE_NAME = "apothiccuriosfix_selftest.log";

    private static final List<String> LINES = new ArrayList<>();
    private static boolean passed = true;
    private static int skipped = 0;

    private SelfTest() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!enabled()) {
            return;
        }
        ApothicCuriosFix.LOGGER.info("[apothiccuriosfix][selftest] starting");
        try {
            run(event.getServer());
        } catch (Throwable t) {
            fail("unexpected exception", t);
        } finally {
            log("RESULT=" + (passed ? "PASS" : "FAIL") + (skipped > 0 ? " (" + skipped + " skipped)" : ""));
            Path path = Path.of(FILE_NAME);
            try {
                Files.writeString(path, String.join(System.lineSeparator(), LINES));
                ApothicCuriosFix.LOGGER.info("[apothiccuriosfix][selftest] written to {}", path.toAbsolutePath());
            } catch (Exception ex) {
                ApothicCuriosFix.LOGGER.error("[apothiccuriosfix][selftest] could not write {}", path.toAbsolutePath(), ex);
            }
            event.getServer().halt(false);
        }
    }

    private static void run(MinecraftServer server) {
        log("mods: apotheosis=" + loaded("apotheosis") + ", attributeslib=" + loaded("attributeslib")
                + ", curios=" + loaded("curios") + ", apothiccurios=" + loaded("apothiccurios"));

        // ============================================================= 1. curios:* loot categories
        Set<String> known = new LinkedHashSet<>(LootCategory.BY_ID.keySet());
        log("LootCategory.BY_ID (" + known.size() + "): " + known);

        int base = 0;
        for (String slot : CurioLootCategories.BASE_SLOTS) {
            String id = CurioLootCategories.PREFIX + slot;
            LootCategory category = LootCategory.BY_ID.get(id);
            boolean ok = category != null && !category.isNone() && id.equals(category.getName());
            if (ok) {
                base++;
            } else {
                fail("loot category " + id + " missing from LootCategory.BY_ID", String.valueOf(category));
            }
        }
        check("all " + CurioLootCategories.BASE_SLOTS.size() + " base curios:* loot categories present in LootCategory.BY_ID",
                base == CurioLootCategories.BASE_SLOTS.size(), base + "/" + CurioLootCategories.BASE_SLOTS.size());

        // The dynamic part: every slot id the Curios API reported must have a category as well. This is the
        // check the 1.20.1a build failed - it only knew eleven hard coded slots.
        Set<String> enumerated = CurioLootCategories.enumeratedSlots();
        log("slots reported by CuriosApi.getSlots(): (" + enumerated.size() + ") " + enumerated);
        List<String> missingEnumerated = new ArrayList<>();
        for (String slot : enumerated) {
            LootCategory category = LootCategory.BY_ID.get(CurioLootCategories.PREFIX + slot);
            if (category == null || category.isNone()) {
                missingEnumerated.add(slot);
            }
        }
        check("every slot enumerated from the Curios API has a curios:* loot category",
                missingEnumerated.isEmpty(),
                enumerated.size() + " enumerated, missing=" + missingEnumerated);
        // Curios 5.14.1 itself ships ten slots (back, belt, body, bracelet, charm, curio, hands, head,
        // necklace, ring); everything else comes from addon data packs. The enumeration must at least deliver
        // that core set - 1.20.1a hard coded eleven ids and never consulted the API at all.
        List<String> coreSlots = List.of("back", "belt", "body", "bracelet", "charm", "curio", "hands", "head",
                "necklace", "ring");
        List<String> missingCore = new ArrayList<>();
        for (String slot : coreSlots) {
            if (!enumerated.contains(slot)) {
                missingCore.add(slot);
            }
        }
        check("the Curios API enumeration returned the whole core slot set of Curios 5.14.1",
                missingCore.isEmpty(), enumerated.size() + " slots, missing core=" + missingCore);

        LootCategory charmCategory = LootCategory.BY_ID.get("curios:charm");
        check("curios:charm claims no vanilla armor slot (LEGS/HEAD/CHEST/FEET absent)",
                charmCategory != null && !claimsArmorSlot(charmCategory),
                charmCategory == null ? "null" : java.util.Arrays.toString(charmCategory.getSlots()));

        // The codec is what produced "Unknown element name: curios:..." before this mod existed. The list holds
        // every name the two packs actually failed on, plus a slot name that exists nowhere - the latter can only
        // resolve through the LootCategory.byId mixin.
        DataResult<Set<LootCategory>> decoded = LootCategory.SET_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "[\"curios:charm\",\"curios:ring\",\"curios:necklace\",\"curios:an_focus\",\"curios:back\","
                        + "\"curios:scroll\",\"curios:spellstone\",\"curios:talisman\",\"curios:brooch\","
                        + "\"curios:__wildcard_probe\",\"chestplate\"]"));
        check("LootCategory.SET_CODEC accepts every curios:* name the packs use (incl. an_focus/back/scroll/spellstone/talisman/brooch)",
                decoded.result().map(set -> set.size() == 11).orElse(false),
                decoded.result().map(String::valueOf).orElseGet(() -> error(decoded)));
        check("LootCategory.byId creates an unknown curios:* name on the fly (byId mixin active)",
                LootCategory.BY_ID.get("curios:__wildcard_probe") != null,
                String.valueOf(LootCategory.BY_ID.get("curios:__wildcard_probe")));
        check("LootCategory.byId still returns null for an unknown non-curio name",
                LootCategory.byId("apotheosis:definitely_not_a_category") == null,
                String.valueOf(LootCategory.byId("apotheosis:definitely_not_a_category")));
        int again = CurioLootCategories.registerAll();
        check("a second registration pass creates nothing (idempotent)", again == 0, "created=" + again);

        // ============================================================= 2. armor / weapons untouched
        ItemStack chestplate = new ItemStack(Items.IRON_CHESTPLATE);
        check("LootCategory.forItem(iron_chestplate) == chestplate",
                LootCategory.forItem(chestplate) == LootCategory.CHESTPLATE,
                String.valueOf(LootCategory.forItem(chestplate)));
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        check("LootCategory.forItem(iron_sword) == sword",
                LootCategory.forItem(sword) == LootCategory.SWORD, String.valueOf(LootCategory.forItem(sword)));

        LootRarity rarity = rarity("apotheosis:rare");
        check("loot rarity apotheosis:rare resolvable", rarity != null, String.valueOf(rarity));
        if (rarity == null) {
            return;
        }

        // ============================================================= 3. data pack side (affixes / gems)
        List<ResourceLocation> curiosityAffixes = new ArrayList<>();
        for (ResourceLocation id : AffixRegistry.INSTANCE.getKeys()) {
            Affix affix = AffixRegistry.INSTANCE.getValue(id);
            if (affix == null) {
                continue;
            }
            String ns = id.getNamespace();
            if (ns.contains("apothiccurios") || ns.equals(ApothicCuriosFix.MODID)) {
                curiosityAffixes.add(id);
            }
        }
        log("affixes whose namespace looks like a curiosity test pack: " + curiosityAffixes);
        List<ResourceLocation> testGems = new ArrayList<>();
        for (ResourceLocation id : GemRegistry.INSTANCE.getKeys()) {
            Gem gem = GemRegistry.INSTANCE.getValue(id);
            if (gem == null) {
                continue;
            }
            if (id.getNamespace().contains("apothiccurios") || id.getNamespace().equals(ApothicCuriosFix.MODID)) {
                testGems.add(id);
            }
        }
        log("gems whose namespace looks like a curiosity test pack: " + testGems);

        // ---- every affix bundled in this jar (data/apothiccurios/affixes) must be applicable to its own category
        // This is the check that decides whether a charm can roll numeric affixes at all: the loot roll only
        // considers affixes for which canApplyTo(stack, category, rarity) is true.
        int packAffixes = 0;
        int packApplicable = 0;
        List<String> notApplicable = new ArrayList<>();
        List<LootRarity> allRarities = new ArrayList<>();
        for (ResourceLocation key : RarityRegistry.INSTANCE.getKeys()) {
            LootRarity value = RarityRegistry.INSTANCE.getValue(key);
            if (value != null) {
                allRarities.add(value);
            }
        }
        log("rarities in the registry: " + allRarities.size());
        for (ResourceLocation id : AffixRegistry.INSTANCE.getKeys()) {
            if (!id.getNamespace().equals("apothiccurios") || !id.getPath().endsWith("_plus")) {
                continue;
            }
            packAffixes++;
            Affix affix = AffixRegistry.INSTANCE.getValue(id);
            String path = id.getPath();
            String slot = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
            LootCategory category = LootCategory.BY_ID.get(CurioLootCategories.PREFIX + slot);
            boolean applicable = affix != null && category != null;
            if (applicable) {
                for (LootRarity value : allRarities) {
                    if (!affix.canApplyTo(ItemStack.EMPTY, category, value)) {
                        applicable = false;
                        notApplicable.add(id + "@" + value);
                        break;
                    }
                }
            } else {
                notApplicable.add(id + " (category " + slot + ")");
            }
            if (applicable) {
                packApplicable++;
            }
        }
        check("jar-bundled affixes (apothiccurios:*_plus): all " + packAffixes + " are applicable to their slot for every rarity",
                packAffixes > 0 && packApplicable == packAffixes,
                packApplicable + "/" + packAffixes + (notApplicable.isEmpty() ? "" : ", not applicable: " + notApplicable.subList(0, Math.min(5, notApplicable.size()))));
        for (ResourceLocation id : AffixRegistry.INSTANCE.getKeys()) {
            if (!id.getNamespace().equals("apothiccurios") || !id.getPath().endsWith("_plus")) {
                continue;
            }
            Affix affix = AffixRegistry.INSTANCE.getValue(id);
            if (affix == null) {
                continue;
            }
            String path = id.getPath();
            String slot = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
            LootCategory category = LootCategory.BY_ID.get(CurioLootCategories.PREFIX + slot);
            LootRarity rare = rarity("apotheosis:rare");
            if (category != null && rare != null) {
                log("  sample: " + id + " type=" + affix.getType() + " canApplyTo(curios:" + slot + ", rare)="
                        + affix.canApplyTo(ItemStack.EMPTY, category, rare));
            }
            break;
        }

        // ============================================================= 3b. gems on curios (原有宝石镶饰品)
        // 白名单里的**原有**宝石:对 curios:<slot> 类目 isValidIn 必须为 true,并且能拿到一个
        // 真正会产出属性修饰符的 bonus;不在名单里的必须保持 false(AP 原生结论)。
        LootCategory ringCategory = LootCategory.BY_ID.get(CurioLootCategories.PREFIX + "ring");
        ItemStack ringStack = firstItemInCurioTag("ring");
        check("curios:ring loot category exists", ringCategory != null, String.valueOf(ringCategory));
        check("found an item in the curios:ring item tag", ringStack != null && !ringStack.isEmpty(),
                ringStack == null ? "null" : String.valueOf(ForgeRegistries.ITEMS.getKey(ringStack.getItem())));
        if (ringCategory != null && ringStack != null && !ringStack.isEmpty() && rarity != null) {
            check("LootCategory.forItem(curio ring) resolves to curios:ring", LootCategory.forItem(ringStack) == ringCategory,
                    String.valueOf(LootCategory.forItem(ringStack)));
            for (ResourceLocation gemId : GemCurioSupport.ALLOWED_GEMS) {
                Gem gem = GemRegistry.INSTANCE.getValue(gemId);
                if (gem == null) {
                    // 依赖别的模组的宝石(例如 twilight/* 要暮色森林)在不装那个模组时根本不存在,跳过而不是判失败
                    log("  skipped (gem not registered in this pack): " + gemId);
                    continue;
                }
                boolean valid = gem != null && gem.isValidIn(ringStack, ItemStack.EMPTY, rarity);
                GemBonus bonus = gem == null ? null : gem.getBonus(ringCategory, rarity).orElse(null);
                List<String> modifierIds = bonus == null ? List.of() : attributeModifiers(bonus, rarity, gem);
                check("existing gem " + gemId + " can be socketed into a curio", valid && bonus != null && !modifierIds.isEmpty(),
                        "isValidIn=" + valid + " bonus=" + (bonus == null ? "none" : bonus.getClass().getSimpleName())
                                + " -> " + modifierIds);
            }
            // 反例用"不在白名单、且不需要别的模组"的宝石:逸流宝石(core/slipstream)
            Gem outside = GemRegistry.INSTANCE.getValue(new ResourceLocation("apotheosis", "core/slipstream"));
            boolean outsideValid = outside != null && outside.isValidIn(ringStack, ItemStack.EMPTY, rarity);
            check("a gem outside the whitelist stays invalid on a curio", outside != null && !outsideValid,
                    "core/slipstream isValidIn=" + outsideValid + (outside == null ? " (gem missing)" : ""));
        }

        // ============================================================= 3c. 完整镶嵌管线(先加孔 → 再镶宝石)
        // 神化分两步:smithing 里"饰品 + 镶孔符"加孔(apotheosis:add_sockets),
        // 再"有孔的饰品 + 宝石"镶入(apotheosis:socketing)。这里两步都真跑一遍。
        if (ringCategory != null && ringStack != null && !ringStack.isEmpty() && rarity != null) {
            Container smithing = new net.minecraft.world.SimpleContainer(3);
            Item sigil = ForgeRegistries.ITEMS.getValue(new ResourceLocation("apotheosis", "sigil_of_socketing"));
            check("apotheosis:sigil_of_socketing exists", sigil != null, String.valueOf(sigil));
            ItemStack socketed = ItemStack.EMPTY;
            if (sigil != null) {
                smithing.setItem(1, ringStack.copy());
                smithing.setItem(2, new ItemStack(sigil));
                var addSockets = server.overworld().getRecipeManager()
                        .getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMITHING, smithing, server.overworld());
                boolean found = addSockets.isPresent();
                check("smithing: curio + sigil matches apotheosis:add_sockets", found,
                        found ? addSockets.get().getId().toString() : "no recipe");
                if (found) {
                    socketed = addSockets.get().assemble(smithing, server.registryAccess());
                    int sockets = SocketHelper.getSockets(socketed);
                    check("after add_sockets the curio has >=1 socket", sockets >= 1,
                            "sockets=" + sockets + " stack=" + socketed);
                }
            }
            if (!socketed.isEmpty()) {
                Gem samuraiGem = GemRegistry.INSTANCE.getValue(new ResourceLocation("apotheosis", "core/samurai"));
                ItemStack gemStack = samuraiGem == null ? ItemStack.EMPTY : gemStack(samuraiGem);
                check("built a real gem stack for socketing", !gemStack.isEmpty(), String.valueOf(gemStack));
                if (!gemStack.isEmpty()) {
                    smithing.setItem(1, socketed.copy());
                    smithing.setItem(2, gemStack);
                    var socketing = server.overworld().getRecipeManager()
                            .getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMITHING, smithing, server.overworld());
                    boolean found = socketing.isPresent();
                    check("smithing: socketed curio + gem matches apotheosis:socketing", found,
                            found ? socketing.get().getId().toString() : "no recipe");
                    if (found) {
                        ItemStack result = socketing.get().assemble(smithing, server.registryAccess());
                        int gems = SocketHelper.getGems(result).size();
                        check("after socketing the curio carries the gem", gems >= 1, "gems=" + gems + " stack=" + result);
                    }
                }
            }
        }
        // ============================================================= 4. the five-vanilla-slot bug
        // An affixed chestplate must keep its normal tooltip, an affixed curio must lose the block.
        ItemStack affixedChest = LootController.createLootItem(chestplate.copy(), rarity, RandomSource.create(1234L));
        int chestAffixes = AffixHelper.getAffixes(affixedChest).size();
        check("createLootItem(chestplate, rare) produced affixes", chestAffixes > 0, "affixes=" + chestAffixes);
        check("tooltip rule: affixed chestplate keeps its attribute block",
                !TooltipRules.hidesAttributeSlotSections(affixedChest), "hidden=" + TooltipRules.hidesAttributeSlotSections(affixedChest));

        ItemStack curio = findCurioStack();
        if (curio.isEmpty()) {
            skip("no item resolves to a curios:* loot category", "add an item to the curios:charm item tag");
        } else {
            log("curio test item: " + curio.getItem() + " -> " + LootCategory.forItem(curio)
                    + " (slots " + java.util.Arrays.toString(LootCategory.forItem(curio).getSlots()) + ")");
            check("LootCategory.forItem(curio) is a curios:* category",
                    CurioLootCategories.isCurioCategory(LootCategory.forItem(curio)),
                    String.valueOf(LootCategory.forItem(curio)));
            check("tooltip rule: a curio is hidden even without affixes (rule is category based)",
                    TooltipRules.hidesAttributeSlotSections(curio),
                    "hidden=" + TooltipRules.hidesAttributeSlotSections(curio));

            // ---- the end-to-end roll requested by the task: charm + apotheosis:rare
            ItemStack rolled = LootController.createLootItem(curio.copy(), rarity, RandomSource.create(0x5EEDL));
            Map<DynamicHolder<? extends Affix>, AffixInstance> rolledAffixes = AffixHelper.getAffixes(rolled);
            List<String> names = new ArrayList<>();
            int attributeAffixes = 0;
            for (Map.Entry<DynamicHolder<? extends Affix>, AffixInstance> entry : rolledAffixes.entrySet()) {
                Affix affix = entry.getKey().get();
                boolean isAttribute = affix instanceof AttributeAffix;
                if (isAttribute) {
                    attributeAffixes++;
                }
                names.add(entry.getKey().getId() + (isAttribute ? "(attribute)" : ""));
            }
            log("createLootItem(" + curio.getItem() + ", apotheosis:rare) -> affixes=" + names);
            check("createLootItem(curio, rare) produced at least one affix", !rolledAffixes.isEmpty(),
                    "affixes=" + rolledAffixes.size());
            if (rolledAffixes.isEmpty()) {
                skip("createLootItem(curio, rare) produced no attribute affix", "no affix declares types: [curios:charm]");
            } else {
                check("createLootItem(curio, rare) produced an attribute affix", attributeAffixes > 0,
                        "attribute affixes=" + attributeAffixes + " of " + rolledAffixes.size());
            }

            ItemStack affixedCurio = rolledAffixes.isEmpty() ? forceAttributeAffix(curio, rarity) : rolled;
            check("curio tooltip: the per-slot attribute block is hidden",
                    TooltipRules.hidesAttributeSlotSections(affixedCurio),
                    "hasAffixes=" + AffixHelper.hasAffixes(affixedCurio));
            bridgeChecks(server, affixedCurio, "charm");
        }

        // ============================================================= 5. bridge on a slot-agnostic item
        // Independent of any data pack: a sword is a MAINHAND item, so it can be used to prove that the
        // desired/apply/idempotent/remove cycle behaves even when no curio item exists in the pack.
        if (curio.isEmpty()) {
            ItemStack affixedSword = forceAttributeAffix(sword, rarity);
            bridgeChecks(server, affixedSword, "charm");
        }

        // ============================================================= 6. tooltip filter (common code)
        // AttributeTooltipFilter is plain common code, so the server can prove that the second, mixin-free
        // layer really removes the "both hands" group from a finished tooltip list.
        List<net.minecraft.network.chat.Component> synthetic = new ArrayList<>();
        synthetic.add(net.minecraft.network.chat.Component.literal("A ring"));
        synthetic.add(net.minecraft.network.chat.Component.empty());
        synthetic.add(net.minecraft.network.chat.Component.translatable("item.modifiers.both_hands"));
        synthetic.add(net.minecraft.network.chat.Component.literal(" +2 Max Health"));
        synthetic.add(net.minecraft.network.chat.Component.empty());
        synthetic.add(net.minecraft.network.chat.Component.literal("A ring"));
        int stripped = AttributeTooltipFilter.stripAttributeGroups(synthetic);
        check("tooltip filter strips the both_hands heading, its line and the separator",
                stripped == 3 && synthetic.size() == 3, "stripped=" + stripped + " left=" + synthetic);

        // ============================================================= 7. client patch wiring
        checkMixinWiring();
    }

    /** Drives desired -> apply -> re-apply -> remove and asserts the UUID/duplication invariants. */
    private static void bridgeChecks(MinecraftServer server, ItemStack stack, String slot) {
        LivingEntity wearer = createWearer(server);
        if (wearer == null) {
            skip("curio bridge", "no wearer entity could be created");
            return;
        }
        List<CurioAffixAttributeBridge.CurioStack> curios =
                List.of(new CurioAffixAttributeBridge.CurioStack(slot, 0, stack));
        List<CurioAffixAttributeBridge.CurioModifier> desired =
                CurioAffixAttributeBridge.desiredModifiers(wearer, curios);
        log("bridge: " + desired.size() + " desired modifier(s) for " + stack.getItem() + " in slot '" + slot + "'");
        check("bridge: affixed item yields at least one attribute modifier", !desired.isEmpty(),
                "desired=" + desired.size());
        if (desired.isEmpty()) {
            return;
        }
        Set<UUID> uuids = new LinkedHashSet<>();
        for (CurioAffixAttributeBridge.CurioModifier modifier : desired) {
            uuids.add(modifier.uuid());
            log("  " + ForgeRegistries.ATTRIBUTES.getKey(modifier.attribute()) + " " + modifier.value() + " "
                    + modifier.operation() + " uuid=" + modifier.uuid() + " affix=" + modifier.affix());
        }
        check("bridge: every modifier has a unique UUID", uuids.size() == desired.size(),
                uuids.size() + " unique of " + desired.size());

        Map<UUID, Attribute> applied = new LinkedHashMap<>();
        int count = CurioAffixAttributeBridge.applyDiff(wearer, desired, applied);
        check("bridge: applyDiff reports " + desired.size() + " applied modifier(s)", count == desired.size(),
                "count=" + count);
        boolean allPresent = true;
        for (CurioAffixAttributeBridge.CurioModifier modifier : desired) {
            AttributeInstance instance = wearer.getAttribute(modifier.attribute());
            double before = instance == null ? 0.0D : instance.getValue();
            AttributeModifier live = instance == null ? null : instance.getModifier(modifier.uuid());
            boolean one = CurioAffixAttributeBridge.countModifiers(wearer, modifier.uuid()) == 1;
            allPresent &= live != null && one;
            log("  wearer " + ForgeRegistries.ATTRIBUTES.getKey(modifier.attribute()) + ": value " + before
                    + " (modifier " + (live == null ? "MISSING" : live.getAmount() + " " + live.getOperation())
                    + ", occurrences=" + CurioAffixAttributeBridge.countModifiers(wearer, modifier.uuid()) + ")");
        }
        check("bridge: every modifier is present exactly once on the wearer", allPresent, "applied=" + applied.size());

        CurioAffixAttributeBridge.applyDiff(wearer, desired, applied);
        boolean stillOne = true;
        for (CurioAffixAttributeBridge.CurioModifier modifier : desired) {
            stillOne &= CurioAffixAttributeBridge.countModifiers(wearer, modifier.uuid()) == 1;
        }
        check("bridge: a second reconcile does not duplicate the UUID", stillOne, "applied=" + applied.size());

        CurioAffixAttributeBridge.applyDiff(wearer, List.of(), applied);
        boolean allGone = true;
        for (CurioAffixAttributeBridge.CurioModifier modifier : desired) {
            allGone &= CurioAffixAttributeBridge.countModifiers(wearer, modifier.uuid()) == 0;
        }
        check("bridge: unequipping removes every modifier again", allGone && applied.isEmpty(),
                "applied=" + applied.size());
    }

    /**
     * Writes a single attribute affix onto {@code stack} so the tooltip / bridge checks do not depend on the
     * presence of a {@code types: [curios:*]} data pack file.
     */
    private static ItemStack forceAttributeAffix(ItemStack stack, LootRarity rarity) {
        ItemStack forced = stack.copy();
        try {
            ResourceLocation affixId = null;
            for (ResourceLocation id : AffixRegistry.INSTANCE.getKeys()) {
                if (AffixRegistry.INSTANCE.getValue(id) instanceof AttributeAffix) {
                    affixId = id;
                    break;
                }
            }
            if (affixId == null) {
                log("  no attribute affix in the registry - cannot force one");
                return forced;
            }
            DynamicHolder<Affix> holder = AffixRegistry.INSTANCE.holder(affixId);
            DynamicHolder<LootRarity> rarityHolder = RarityRegistry.INSTANCE.holder(rarity);
            AffixHelper.setRarity(forced, rarity);
            AffixInstance instance = new AffixInstance(holder, forced, rarityHolder, 0.0F);
            AffixHelper.setAffixes(forced, Map.<DynamicHolder<? extends Affix>, AffixInstance>of(holder, instance));
            log("  forced attribute affix " + affixId + " onto " + forced.getItem());
        } catch (Throwable t) {
            log("  could not force an attribute affix (" + t + ")");
        }
        return forced;
    }

    /** First item of the registry that resolves to a {@code curios:*} loot category. */
    private static ItemStack findCurioStack() {
        LootCategory charm = LootCategory.BY_ID.get("curios:charm");
        // Prefer an item in the charm tag so the message is readable, then fall back to any curio category.
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) {
                continue;
            }
            LootCategory category = LootCategory.forItem(stack);
            if (charm != null && category == charm) {
                return stack;
            }
        }
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ItemStack stack = new ItemStack(item);
            if (!stack.isEmpty() && CurioLootCategories.isCurioCategory(LootCategory.forItem(stack))) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean claimsArmorSlot(LootCategory category) {
        for (EquipmentSlot slot : category.getSlots()) {
            if (slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
                    || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ client patch wiring

    private static void checkMixinWiring() {
        String config = resource("/apothiccuriosfix.mixins.json");
        check("mixin config shipped in the jar", config != null, "apothiccuriosfix.mixins.json");
        if (config != null) {
            check("mixin config lists AttributesLibClientMixin under \"client\"",
                    config.contains("AttributesLibClientMixin") && config.contains("\"client\""),
                    "entries=" + config.replaceAll("\\s+", " "));
            check("mixin config lists LootCategoryMixin under the common \"mixins\" list",
                    config.contains("LootCategoryMixin"), "entries=" + config.replaceAll("\\s+", " "));
            check("mixin config is non-fatal (\"required\": false)",
                    config.contains("\"required\": false"), "required=false");
        }
        String manifest = resource("/META-INF/MANIFEST.MF");
        check("jar manifest advertises MixinConfigs=apothiccuriosfix.mixins.json",
                manifest != null && manifest.replace(" ", "").contains("MixinConfigs:apothiccuriosfix.mixins.json"),
                manifest == null ? "no manifest" : "ok");
        try {
            // Just resolving the target class is all a dedicated server can do: the mixin and every one of
            // the target's other methods reference client-only types, so the class must not be initialised
            // (and cannot be linked) here. The exact descriptor of applyModifierTooltips is verified at
            // build time instead (javap on both the target and this mod's handler).
            Class.forName("dev.shadowsoffire.attributeslib.client.AttributesLibClient", false,
                    SelfTest.class.getClassLoader());
            log("  AttributesLibClient is on this class loader (client-only mixin, not applied on a server)");
        } catch (Throwable t) {
            log("  AttributesLibClient not loadable here (" + t.getClass().getSimpleName()
                    + ") - expected on a dedicated server, the mixin is client-only");
        }
    }

    @Nullable
    private static String resource(String path) {
        try (InputStream in = SelfTest.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    private static LivingEntity createWearer(MinecraftServer server) {
        ServerLevel level = server.overworld();
        try {
            ServerPlayer player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "apothiccuriosfix_selftest"));
            log("  wearer: ServerPlayer instance (no network connection)");
            return player;
        } catch (Throwable t) {
            log("  ServerPlayer creation failed (" + t + ")");
            return null;
        }
    }

    @Nullable
    private static LootRarity rarity(String id) {
        LootRarity rarity = RarityRegistry.INSTANCE.getValue(new ResourceLocation(id));
        if (rarity != null) {
            return rarity;
        }
        for (ResourceLocation key : RarityRegistry.INSTANCE.getKeys()) {
            if ("rare".equals(key.getPath())) {
                return RarityRegistry.INSTANCE.getValue(key);
            }
        }
        return null;
    }

    private static boolean loaded(String modId) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(modId);
    }

    private static String error(DataResult<?> result) {
        return result.error().map(err -> err.message()).orElse("unknown error");
    }

    /** 找一个属于 {@code curios:<slot>} 物品标签的物品,用来当"饰品样本"。 */
    @Nullable
    private static ItemStack firstItemInCurioTag(String slot) {
        var tag = ItemTags.create(new ResourceLocation("curios", slot));
        for (Item item : ForgeRegistries.ITEMS) {
            ItemStack stack = new ItemStack(item);
            if (stack.is(tag)) {
                return stack;
            }
        }
        return null;
    }

    /**
     * 这个 bonus 在当前稀有度下真正会产出的属性(空列表说明镶上去也没效果)。
     * 必须传**真的宝石堆**:{@code AttributeBonus#addModifiers} 会从它身上取 UUID
     * ({@code GemItem.getUUIDs(stack).get(0)}),空堆会直接抛越界。
     */
    private static List<String> attributeModifiers(GemBonus bonus, LootRarity rarity, Gem gem) {
        List<String> ids = new ArrayList<>();
        try {
            ItemStack gemStack = gemStack(gem);
            if (gemStack.isEmpty()) {
                return ids;
            }
            bonus.addModifiers(gemStack, rarity, (attribute, modifier) -> {
                if (attribute == null || modifier == null) {
                    return;
                }
                var key = ForgeRegistries.ATTRIBUTES.getKey(attribute);
                ids.add(String.valueOf(key) + " " + modifier.getAmount() + " " + modifier.getOperation());
            });
        } catch (RuntimeException | LinkageError ignored) {
            // 取不到就当没有
        }
        return ids;
    }

    /**
     * 造一颗"真的"宝石物品堆:gem id + UUID + **稀有度**。
     * 稀有度不能省 —— {@code GemInstance.unsocketed(stack)} 要求 gem 与 rarity 两个 holder 都 bound,
     * 少一个 {@code SocketingRecipe} 第一步就 false(第一版自检就是漏了它,导致"镶不了"的假象)。
     */
    private static ItemStack gemStack(Gem gem) {
        return gemStack(gem, rarity("apotheosis:rare"));
    }

    private static ItemStack gemStack(Gem gem, LootRarity rarity) {
        Item gemItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation("apotheosis", "gem"));
        if (gemItem == null || gem == null || rarity == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(gemItem);
        GemItem.setGem(stack, gem);
        AffixHelper.setRarity(stack, rarity);
        return stack;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            log("[ OK ] " + name + (detail == null ? "" : "  (" + detail + ")"));
        } else {
            fail(name, detail);
        }
    }

    private static void skip(String name, String reason) {
        skipped++;
        log("[SKIP] " + name + (reason == null ? "" : "  (" + reason + ")"));
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
        ApothicCuriosFix.LOGGER.info("[apothiccuriosfix][selftest] " + line);
    }
}
