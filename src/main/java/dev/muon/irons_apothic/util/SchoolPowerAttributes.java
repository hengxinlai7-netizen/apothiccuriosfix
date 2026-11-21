package dev.muon.irons_apothic.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Maps an Iron's Spells school id ({@code irons_spellbooks:fire}) onto the school power attribute that the
 * school uses ({@code irons_spellbooks:fire_spell_power}).
 *
 * <p>1.20.1 Iron's Spellbooks exposes the schools as a {@code RegistryObject} plus a plain
 * {@link ResourceLocation} constant, and the power attribute of a {@code SchoolType} is private (only
 * reachable through {@code SchoolType#getPowerFor(LivingEntity)}), so the association is kept here.</p>
 *
 * <p>Everything is resolved lazily and every entry point is safe when Iron's Spellbooks is absent: the
 * lookups then return {@code null}/{@code false} instead of raising {@link NoClassDefFoundError}.</p>
 */
public final class SchoolPowerAttributes {

    private SchoolPowerAttributes() {
    }

    private static volatile boolean unavailable;

    private static final class Holder {
        private static final Map<ResourceLocation, Supplier<Attribute>> BY_SCHOOL = build();
        private static final Map<ResourceLocation, ResourceLocation> ATTRIBUTE_IDS = ids();

        private static Map<ResourceLocation, Supplier<Attribute>> build() {
            Map<ResourceLocation, Supplier<Attribute>> map = new LinkedHashMap<>();
            map.put(io.redspace.ironsspellbooks.api.registry.SchoolRegistry.FIRE_RESOURCE,
                    io.redspace.ironsspellbooks.api.registry.AttributeRegistry.FIRE_SPELL_POWER);
            map.put(io.redspace.ironsspellbooks.api.registry.SchoolRegistry.ICE_RESOURCE,
                    io.redspace.ironsspellbooks.api.registry.AttributeRegistry.ICE_SPELL_POWER);
            map.put(io.redspace.ironsspellbooks.api.registry.SchoolRegistry.LIGHTNING_RESOURCE,
                    io.redspace.ironsspellbooks.api.registry.AttributeRegistry.LIGHTNING_SPELL_POWER);
            map.put(io.redspace.ironsspellbooks.api.registry.SchoolRegistry.HOLY_RESOURCE,
                    io.redspace.ironsspellbooks.api.registry.AttributeRegistry.HOLY_SPELL_POWER);
            map.put(io.redspace.ironsspellbooks.api.registry.SchoolRegistry.ENDER_RESOURCE,
                    io.redspace.ironsspellbooks.api.registry.AttributeRegistry.ENDER_SPELL_POWER);
            map.put(io.redspace.ironsspellbooks.api.registry.SchoolRegistry.BLOOD_RESOURCE,
                    io.redspace.ironsspellbooks.api.registry.AttributeRegistry.BLOOD_SPELL_POWER);
            map.put(io.redspace.ironsspellbooks.api.registry.SchoolRegistry.EVOCATION_RESOURCE,
                    io.redspace.ironsspellbooks.api.registry.AttributeRegistry.EVOCATION_SPELL_POWER);
            map.put(io.redspace.ironsspellbooks.api.registry.SchoolRegistry.NATURE_RESOURCE,
                    io.redspace.ironsspellbooks.api.registry.AttributeRegistry.NATURE_SPELL_POWER);
            map.put(io.redspace.ironsspellbooks.api.registry.SchoolRegistry.ELDRITCH_RESOURCE,
                    io.redspace.ironsspellbooks.api.registry.AttributeRegistry.ELDRITCH_SPELL_POWER);
            return Map.copyOf(map);
        }

        private static Map<ResourceLocation, ResourceLocation> ids() {
            Map<ResourceLocation, ResourceLocation> map = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, Supplier<Attribute>> entry : BY_SCHOOL.entrySet()) {
                ResourceLocation attributeId = idOf(entry.getValue());
                if (attributeId != null) {
                    map.put(attributeId, entry.getKey());
                }
            }
            return Map.copyOf(map);
        }

        @Nullable
        private static ResourceLocation idOf(Supplier<Attribute> supplier) {
            return supplier instanceof RegistryObject<?> object ? object.getId() : null;
        }
    }

    private static Map<ResourceLocation, Supplier<Attribute>> bySchool() {
        if (unavailable) {
            return Map.of();
        }
        try {
            return Holder.BY_SCHOOL;
        } catch (Throwable t) {
            unavailable = true;
            Debug.log("Iron's Spellbooks school/attribute registry unavailable: %s", t);
            return Map.of();
        }
    }

    private static Map<ResourceLocation, ResourceLocation> attributeIds() {
        if (unavailable) {
            return Map.of();
        }
        try {
            return Holder.ATTRIBUTE_IDS;
        } catch (Throwable t) {
            unavailable = true;
            Debug.log("Iron's Spellbooks school/attribute registry unavailable: %s", t);
            return Map.of();
        }
    }

    /** @return the school power attribute of the given school id, or {@code null} when unknown. */
    @Nullable
    public static Attribute forSchool(@Nullable ResourceLocation schoolId) {
        if (schoolId == null) {
            return null;
        }
        Supplier<Attribute> supplier = bySchool().get(schoolId);
        return supplier == null ? null : supplier.get();
    }

    /** @return the registry id of the power attribute of the given school, or {@code null} when unknown. */
    @Nullable
    public static String idForSchool(@Nullable ResourceLocation schoolId) {
        if (schoolId == null) {
            return null;
        }
        Supplier<Attribute> supplier = bySchool().get(schoolId);
        if (!(supplier instanceof RegistryObject<?> object)) {
            return null;
        }
        return object.getId().toString();
    }

    public static boolean isKnownSchool(@Nullable ResourceLocation schoolId) {
        return schoolId != null && bySchool().containsKey(schoolId);
    }

    /**
     * The school power attributes are Iron's {@code MagicPercentAttribute}s (default value 1.0), so their
     * tooltips are rendered as {@code value * 100 %}. Every other attribute is rendered as a flat number.
     */
    public static boolean isPercentAttribute(@Nullable Attribute attribute) {
        if (attribute == null) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(attribute);
        return id != null && attributeIds().containsKey(id);
    }

    /** @return the school id that owns the given power attribute, or {@code null}. */
    @Nullable
    public static ResourceLocation schoolOf(@Nullable Attribute attribute) {
        if (attribute == null) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(attribute);
        return id == null ? null : attributeIds().get(id);
    }
}
