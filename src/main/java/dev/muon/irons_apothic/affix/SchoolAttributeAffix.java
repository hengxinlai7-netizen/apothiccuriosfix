package dev.muon.irons_apothic.affix;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.muon.irons_apothic.util.Debug;
import dev.muon.irons_apothic.util.SchoolPowerAttributes;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.placebo.codec.PlaceboCodecs;
import dev.shadowsoffire.placebo.util.StepFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Apotheosis 1.20.1 (7.4.8) affix that grants one Iron's Spells school attribute
 * (e.g. {@code irons_spellbooks:fire_spell_power}).
 *
 * <p>JSON shape - the keys are the real 7.4.8 ones, mirroring the built in {@code apotheosis:attribute} affix:</p>
 * <pre>
 * {
 *   "type": "irons_apothic:school_attribute",
 *   "attribute": "irons_spellbooks:fire_spell_power",   // optional when "school" is given
 *   "school": "irons_spellbooks:fire",                  // optional, resolves the attribute above
 *   "operation": "ADDITION",
 *   "values": { "rare": { "min": 0.05, "steps": 2, "step": 0.02 } },
 *   "types": [ "ring", "necklace" ]
 * }
 * </pre>
 *
 * <p>The attribute is kept as a plain string (instead of a registry codec) on purpose: with a registry
 * codec a data pack that mentions {@code irons_spellbooks:fire_spell_power} fails to parse whenever Iron's
 * Spellbooks is not installed, which fills the log with codec errors. With a string the file always parses,
 * the attribute is resolved lazily and the affix simply becomes inert ({@link #canApplyTo} returns false)
 * when the attribute does not exist.</p>
 *
 * <p>In 1.20.1 the attribute modifiers of <em>curio</em> items cannot ride on Forge's
 * {@code ItemAttributeModifierEvent} (Curios 5.14.1 never fires it), so
 * {@code CurioAffixAttributeBridge} mirrors these modifiers onto the wearer. For armor, weapons and held
 * items the regular Apotheosis path calls {@link #addModifiers} directly.</p>
 */
public class SchoolAttributeAffix extends Affix {

    public static final Codec<SchoolAttributeAffix> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.optionalFieldOf("attribute").forGetter(a -> Optional.of(a.attributeId)),
            ResourceLocation.CODEC.optionalFieldOf("school").forGetter(a -> a.schoolId),
            PlaceboCodecs.enumCodec(Operation.class).fieldOf("operation").forGetter(a -> a.operation),
            LootRarity.mapCodec(StepFunction.CODEC).fieldOf("values").forGetter(a -> a.values),
            LootCategory.SET_CODEC.optionalFieldOf("types", Set.of()).forGetter(a -> a.types)
    ).apply(inst, SchoolAttributeAffix::create));

    /** Name of the attribute modifier this affix puts on items/players. */
    public static final String MODIFIER_NAME = "irons_apothic:school_attribute";

    protected final String attributeId;
    protected final Optional<ResourceLocation> schoolId;
    protected final Operation operation;
    protected final Map<LootRarity, StepFunction> values;
    protected final Set<LootCategory> types;
    /** Resolved attribute, or {@code null} when the attribute id is unknown in this instance. */
    @Nullable
    protected final Attribute attribute;

    protected SchoolAttributeAffix(String attributeId, Optional<ResourceLocation> schoolId, Operation operation,
                                   Map<LootRarity, StepFunction> values, Set<LootCategory> types) {
        super(AffixType.STAT);
        this.attributeId = attributeId;
        this.schoolId = schoolId;
        this.operation = operation;
        this.values = values;
        this.types = types;
        this.attribute = resolve(attributeId);
    }

    @Nullable
    private static Attribute resolve(String attributeId) {
        ResourceLocation id = ResourceLocation.tryParse(attributeId);
        return id == null ? null : ForgeRegistries.ATTRIBUTES.getValue(id);
    }

    private static SchoolAttributeAffix create(Optional<String> declaredAttribute, Optional<ResourceLocation> school,
                                               Operation operation, Map<LootRarity, StepFunction> values,
                                               Set<LootCategory> types) {
        String attributeId = declaredAttribute.orElse(null);
        if (attributeId == null && school.isPresent()) {
            attributeId = SchoolPowerAttributes.idForSchool(school.get());
            if (attributeId == null) {
                attributeId = school.get().toString();
                Debug.log("school_attribute: no power attribute known for school %s", school.get());
            }
        }
        if (attributeId == null) {
            throw new IllegalArgumentException(
                    "irons_apothic:school_attribute needs either an 'attribute' id or a 'school'");
        }
        return new SchoolAttributeAffix(attributeId, school, operation, values, types);
    }

    @Override
    public Codec<? extends Affix> getCodec() {
        return CODEC;
    }

    // ------------------------------------------------------------------ data accessors

    public String attributeIdString() {
        return this.attributeId;
    }

    @Nullable
    public Attribute attribute() {
        return this.attribute;
    }

    public Operation operation() {
        return this.operation;
    }

    public Map<LootRarity, StepFunction> values() {
        return this.values;
    }

    public Set<LootCategory> types() {
        return this.types;
    }

    public Optional<ResourceLocation> schoolId() {
        return this.schoolId;
    }

    /** Raw attribute delta for the given rarity/level, or {@code 0} when that rarity is not rolled. */
    public double valueFor(LootRarity rarity, float level) {
        StepFunction function = this.values.get(rarity);
        return function == null ? 0.0D : function.get(level);
    }

    // ------------------------------------------------------------------ affix behaviour

    @Override
    public boolean canApplyTo(ItemStack stack, LootCategory category, LootRarity rarity) {
        if (category == null || category.isNone()) {
            return false;
        }
        if (this.attribute == null) {
            // Attribute is not present in this instance -> never roll an affix that could not do anything.
            return false;
        }
        if (!this.values.containsKey(rarity)) {
            return false;
        }
        return this.types.isEmpty() || this.types.contains(category);
    }

    @Override
    public void addModifiers(ItemStack stack, LootRarity rarity, float level, EquipmentSlot slot,
                             BiConsumer<Attribute, AttributeModifier> consumer) {
        Attribute attribute = this.attribute;
        if (attribute == null) {
            return;
        }
        StepFunction function = this.values.get(rarity);
        if (function == null) {
            return;
        }
        double value = function.get(level);
        if (value == 0.0D) {
            return;
        }
        AttributeModifier modifier = createModifier(modifierUuid(stack), value);
        consumer.accept(attribute, modifier);
        Debug.log("addModifiers attr=%s value=%s op=%s slot=%s name=%s uuid=%s",
                this.attributeId, value, this.operation, slot, modifier.getName(), modifier.getId());
    }

    public AttributeModifier createModifier(UUID uuid, double value) {
        return new AttributeModifier(uuid, MODIFIER_NAME, value, this.operation);
    }

    /**
     * Per-item deterministic UUID. Apotheosis hashes its own UUIDs from the registry id of the affix, but
     * that id is only bound while the affix sits in the data driven {@code AffixRegistry}, so the
     * item + attribute + operation + school combination is hashed here instead.
     */
    public UUID modifierUuid(ItemStack stack) {
        return UUID.nameUUIDFromBytes(modifierKey(stack).getBytes(StandardCharsets.UTF_8));
    }

    protected String modifierKey(ItemStack stack) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return "irons_apothic/school_attribute"
                + "/" + this.attributeId
                + "/" + this.schoolId.map(ResourceLocation::toString).orElse("-")
                + "/" + this.operation.name()
                + "/" + itemId;
    }

    // ------------------------------------------------------------------ tooltip

    @Override
    public MutableComponent getDescription(ItemStack stack, LootRarity rarity, float level) {
        return Component.translatable("affix.irons_apothic.school_attribute.desc",
                attributeName(), formattedValue(valueFor(rarity, level)));
    }

    @Override
    public Component getAugmentingText(ItemStack stack, LootRarity rarity, float level) {
        MutableComponent description = this.getDescription(stack, rarity, level);
        StepFunction function = this.values.get(rarity);
        if (function == null) {
            return description;
        }
        return description.append(valueBounds(formattedValue(function.min()), formattedValue(function.max())));
    }

    protected Component attributeName() {
        Attribute attribute = this.attribute;
        return attribute == null
                ? Component.literal(this.attributeId)
                : Component.translatable(attribute.getDescriptionId());
    }

    protected Component formattedValue(double value) {
        if (SchoolPowerAttributes.isPercentAttribute(this.attribute)) {
            return Component.literal("+" + fmt((float) (value * 100.0D)) + "%");
        }
        return Component.literal((value > 0 ? "+" : "") + fmt((float) value));
    }
}
