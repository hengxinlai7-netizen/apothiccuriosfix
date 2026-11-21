package dev.muon.irons_apothic.selftest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.muon.irons_apothic.IronsApothic;
import dev.muon.irons_apothic.affix.ManaCostAffix;
import dev.muon.irons_apothic.affix.SchoolAttributeAffix;
import dev.muon.irons_apothic.category.CurioLootCategories;
import dev.muon.irons_apothic.util.SchoolPowerAttributes;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Self check that runs inside the mod class loader of a real (dedicated) server when the JVM system property
 * {@code -Dirons_apothic.selftest=true} is set. It stops the server once the checks are done, and writes
 * {@code irons_apothic_selftest.log} next to the server jar so the result can be collected without scrolling
 * through the whole log.
 */
public final class SelfTest {

    public static final String PROPERTY = "irons_apothic.selftest";
    private static final String FILE_NAME = "irons_apothic_selftest.log";

    private static final List<String> LINES = new ArrayList<>();
    private static boolean passed = true;

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
        IronsApothic.LOGGER.info("[irons_apothic][selftest] starting");
        try {
            run();
        } catch (Throwable t) {
            fail("unexpected exception", t);
        } finally {
            log("RESULT=" + (passed ? "PASS" : "FAIL"));
            Path path = Path.of(FILE_NAME);
            try {
                Files.writeString(path, String.join(System.lineSeparator(), LINES));
                IronsApothic.LOGGER.info("[irons_apothic][selftest] written to {}", path.toAbsolutePath());
            } catch (Exception ex) {
                IronsApothic.LOGGER.error("[irons_apothic][selftest] could not write {}", path.toAbsolutePath(), ex);
            }
            event.getServer().halt(false);
        }
    }

    private static void run() {
        List<LootRarity> rarities = List.of(
                rarity("apotheosis:rare"), rarity("apotheosis:common"), rarity("apotheosis:epic"));
        LootRarity rare = rarities.get(0) != null ? rarities.get(0)
                : rarities.get(1) != null ? rarities.get(1) : rarities.get(2);
        check("loot rarity resolvable", rare != null, "rarity=" + (rare == null ? "null" : RarityRegistry.INSTANCE.getKey(rare)));
        if (rare == null) {
            return;
        }

        // ------------------------------------------------------------------ loot categories
        CurioLootCategories.register();
        LootCategory ring = LootCategory.byId("ring");
        LootCategory necklace = LootCategory.byId("necklace");
        check("loot category 'ring' registered", ring != null && !ring.isNone(), String.valueOf(ring));
        check("loot category 'necklace' registered", necklace != null && !necklace.isNone(), String.valueOf(necklace));
        DataResult<java.util.Set<LootCategory>> cats = LootCategory.SET_CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("[\"ring\",\"necklace\",\"chestplate\"]"));
        check("LootCategory.SET_CODEC accepts ring/necklace", cats.result().map(s -> s.size() == 3).orElse(false),
                cats.result().map(String::valueOf).orElseGet(() -> error(cats)));

        // ------------------------------------------------------------------ data pack affixes
        for (String id : List.of(
                "irons_apothic:ring/school_attribute/fire_power",
                "irons_apothic:ring/mana_cost/fire_mana_cost",
                "irons_apothic:necklace/school_attribute/ice_power",
                "irons_apothic:necklace/mana_cost/arcane_mana_cost")) {
            ResourceLocation key = new ResourceLocation(id);
            boolean loaded = dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry.INSTANCE.getKeys().contains(key);
            String type = loaded
                    ? dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry.INSTANCE.getValue(key).getClass().getSimpleName()
                    : "<missing>";
            check("datapack affix loaded " + id, loaded, type);
        }

        // ------------------------------------------------------------------ SchoolAttributeAffix
        String attributeJson = """
                {
                  "type": "irons_apothic:school_attribute",
                  "attribute": "minecraft:generic.max_health",
                  "operation": "ADDITION",
                  "values": {"rare": {"min": 2.0, "steps": 2, "step": 1.0}},
                  "types": ["ring"]
                }""";
        DataResult<SchoolAttributeAffix> decoded = SchoolAttributeAffix.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(attributeJson));
        check("SchoolAttributeAffix decode", decoded.result().isPresent(), decoded.result().map(String::valueOf).orElseGet(() -> error(decoded)));
        Optional<SchoolAttributeAffix> maybeAffix = decoded.result();
        if (maybeAffix.isEmpty()) {
            return;
        }
        SchoolAttributeAffix affix = maybeAffix.get();
        check("SchoolAttributeAffix attribute resolved", affix.attribute() != null, "attribute=" + affix.attributeIdString());
        check("SchoolAttributeAffix value level=0", Math.abs(affix.valueFor(rare, 0.0F) - 2.0D) < 1e-6, String.valueOf(affix.valueFor(rare, 0.0F)));
        check("SchoolAttributeAffix value level=1", Math.abs(affix.valueFor(rare, 1.0F) - 4.0D) < 1e-6, String.valueOf(affix.valueFor(rare, 1.0F)));

        // addModifiers must emit exactly one modifier with the right attribute/value/operation
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        List<AttributeModifier> produced = new ArrayList<>();
        affix.addModifiers(stack, rare, 0.0F, EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            produced.add(modifier);
            log("  addModifiers -> attribute=" + net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getKey(attribute)
                    + " amount=" + modifier.getAmount()
                    + " operation=" + modifier.getOperation()
                    + " name=" + modifier.getName()
                    + " uuid=" + modifier.getId());
        });
        boolean modifierOk = produced.size() == 1
                && Math.abs(produced.get(0).getAmount() - 2.0D) < 1e-6
                && produced.get(0).getOperation() == AttributeModifier.Operation.ADDITION
                && affix.attribute() == Attributes.MAX_HEALTH;
        check("SchoolAttributeAffix.addModifiers", modifierOk, "count=" + produced.size());

        // stability: the same stack must produce the same UUID twice
        List<java.util.UUID> again = new ArrayList<>();
        affix.addModifiers(stack, rare, 0.0F, EquipmentSlot.MAINHAND, (attribute, modifier) -> again.add(modifier.getId()));
        check("SchoolAttributeAffix modifier uuid stable", again.equals(List.of(produced.get(0).getId())), String.valueOf(again));

        // canApplyTo
        check("canApplyTo(wrong category SWORD) == false", !affix.canApplyTo(stack, LootCategory.SWORD, rare), "SWORD");
        check("canApplyTo(NONE) == false", !affix.canApplyTo(stack, LootCategory.NONE, rare), "NONE");
        check("canApplyTo(ring) == true", affix.canApplyTo(stack, CurioLootCategories.ring(), rare), "ring");
        LootRarity uncommon = rarity("apotheosis:uncommon");
        if (uncommon != null) {
            check("canApplyTo(unrolled rarity) == false", !affix.canApplyTo(stack, CurioLootCategories.ring(), uncommon), "uncommon");
        }

        // round trip: decode -> encode -> decode -> encode must be identical
        DataResult<JsonElement> encoded = SchoolAttributeAffix.CODEC.encodeStart(JsonOps.INSTANCE, affix);
        check("SchoolAttributeAffix encode", encoded.result().isPresent(), encoded.result().map(String::valueOf).orElseGet(() -> error(encoded)));
        encoded.result().ifPresent(first -> {
            DataResult<SchoolAttributeAffix> second = SchoolAttributeAffix.CODEC.parse(JsonOps.INSTANCE, first);
            check("SchoolAttributeAffix re-decode", second.result().isPresent(), error(second));
            second.result().ifPresent(reparsed -> {
                DataResult<JsonElement> reEncoded = SchoolAttributeAffix.CODEC.encodeStart(JsonOps.INSTANCE, reparsed);
                String a = first.toString();
                String b = reEncoded.result().map(Object::toString).orElse("<error>");
                check("SchoolAttributeAffix codec round-trip", a.equals(b), a + " == " + b);
            });
        });

        // school -> attribute mapping (needs Iron's Spellbooks, skipped when absent)
        String schoolJson = """
                {
                  "type": "irons_apothic:school_attribute",
                  "school": "irons_spellbooks:fire",
                  "operation": "ADDITION",
                  "values": {"rare": 0.1},
                  "types": ["ring"]
                }""";
        DataResult<SchoolAttributeAffix> schoolDecoded = SchoolAttributeAffix.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(schoolJson));
        check("SchoolAttributeAffix(school) decode", schoolDecoded.result().isPresent(), error(schoolDecoded));
        schoolDecoded.result().ifPresent(schoolAffix -> {
            boolean iron = SchoolPowerAttributes.isKnownSchool(new ResourceLocation("irons_spellbooks", "fire"));
            log("  school -> attribute: " + schoolAffix.attributeIdString() + " (iron's present=" + iron + ")");
            if (iron) {
                check("school 'irons_spellbooks:fire' -> fire_spell_power",
                        "irons_spellbooks:fire_spell_power".equals(schoolAffix.attributeIdString())
                                && schoolAffix.attribute() != null,
                        schoolAffix.attributeIdString());
            }
        });

        // ------------------------------------------------------------------ ManaCostAffix
        String manaJson = """
                {
                  "type": "irons_apothic:mana_cost",
                  "schools": ["irons_spellbooks:fire"],
                  "values": {"rare": {"min": 0.06, "steps": 2, "step": 0.02}},
                  "types": ["ring"]
                }""";
        DataResult<ManaCostAffix> manaDecoded = ManaCostAffix.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(manaJson));
        check("ManaCostAffix decode", manaDecoded.result().isPresent(), error(manaDecoded));
        manaDecoded.result().ifPresent(mana -> {
            float at0 = mana.getReductionPercent(rare, 0.0F);
            float at1 = mana.getReductionPercent(rare, 1.0F);
            log("  reduction rare@0=" + at0 + " rare@1=" + at1 + " baseManacost100 -> " + Math.round(100 * (1 - at0)));
            check("ManaCostAffix reduction level=0", Math.abs(at0 - 0.06F) < 1e-6F, String.valueOf(at0));
            check("ManaCostAffix reduction level=1", Math.abs(at1 - 0.10F) < 1e-6F, String.valueOf(at1));
            check("ManaCostAffix school match", mana.matchesSchool(new ResourceLocation("irons_spellbooks", "fire")), "fire");
            check("ManaCostAffix school mismatch", !mana.matchesSchool(new ResourceLocation("irons_spellbooks", "ice")), "ice");
            check("ManaCostAffix reduce 100 by 6%", ManaCostAffix.applyReduction(100, 0.06F) == 94, String.valueOf(ManaCostAffix.applyReduction(100, 0.06F)));
            check("ManaCostAffix reduce 40 by 25%", ManaCostAffix.applyReduction(40, 0.25F) == 30, String.valueOf(ManaCostAffix.applyReduction(40, 0.25F)));
            check("ManaCostAffix clamps to 90%", ManaCostAffix.applyReduction(100, 1.5F) == 10, String.valueOf(ManaCostAffix.applyReduction(100, 1.5F)));
            check("ManaCostAffix never negative", ManaCostAffix.applyReduction(0, 0.5F) == 0, String.valueOf(ManaCostAffix.applyReduction(0, 0.5F)));
            check("ManaCostAffix no affix on plain stack", ManaCostAffix.reductionOnStack(stack, null) == 0.0F, "0");

            DataResult<JsonElement> manaEncoded = ManaCostAffix.CODEC.encodeStart(JsonOps.INSTANCE, mana);
            check("ManaCostAffix encode", manaEncoded.result().isPresent(), error(manaEncoded));
            manaEncoded.result().ifPresent(first -> {
                DataResult<ManaCostAffix> second = ManaCostAffix.CODEC.parse(JsonOps.INSTANCE, first);
                check("ManaCostAffix re-decode", second.result().isPresent(), error(second));
                second.result().ifPresent(reparsed -> {
                    DataResult<JsonElement> reEncoded = ManaCostAffix.CODEC.encodeStart(JsonOps.INSTANCE, reparsed);
                    String a = first.toString();
                    String b = reEncoded.result().map(Object::toString).orElse("<error>");
                    check("ManaCostAffix codec round-trip", a.equals(b), a + " == " + b);
                });
            });
        });

        // ------------------------------------------------------------------ bridge sanity
        check("curio bridge: no curios -> no modifiers",
                dev.muon.irons_apothic.event.CurioAffixAttributeBridge.desiredModifiers(List.of()).isEmpty(), "[]");

        // ------------------------------------------------------------------ attribute tooltip sanity
        log("  percent-attribute check: max_health=" + SchoolPowerAttributes.isPercentAttribute(Attributes.MAX_HEALTH)
                + ", fire_spell_power=" + SchoolPowerAttributes.isPercentAttribute(
                        net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getValue(
                                new ResourceLocation("irons_spellbooks", "fire_spell_power"))));
    }

    private static LootRarity rarity(String id) {
        return RarityRegistry.INSTANCE.getValue(new ResourceLocation(id));
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) {
            passed = false;
        }
        log((ok ? "PASS  " : "FAIL  ") + name + "  [" + detail + "]");
    }

    private static void fail(String name, Throwable t) {
        passed = false;
        log("FAIL  " + name + "  [" + t + "]");
        IronsApothic.LOGGER.error("[irons_apothic][selftest] " + name, t);
    }

    private static String error(DataResult<?> result) {
        return result.error().map(Object::toString).orElse("unknown error");
    }

    private static void log(String line) {
        LINES.add(line);
        IronsApothic.LOGGER.info("[irons_apothic][selftest] {}", line);
    }
}
