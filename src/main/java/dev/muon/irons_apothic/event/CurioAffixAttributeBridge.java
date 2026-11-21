package dev.muon.irons_apothic.event;

import dev.muon.irons_apothic.affix.SchoolAttributeAffix;
import dev.muon.irons_apothic.util.Debug;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.SlotResult;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Grants the attributes of {@link SchoolAttributeAffix}es that sit on <em>curio</em> items to their wearer.
 *
 * <p>Apotheosis 7.4.8 applies affix attributes through Forge's {@code ItemAttributeModifierEvent}, and
 * Curios 5.14.1 never fires that event for curio slots, so a ring or necklace with a school power affix
 * would show the tooltip line but change nothing. This bridge recomputes the desired modifier set once per
 * second, adds/updates missing {@link AttributeModifier}s as transient modifiers on the player and removes
 * modifiers it added earlier but that are no longer wanted (unequipped / rerolled / level changed).</p>
 */
public final class CurioAffixAttributeBridge {

    /** player uuid -> (modifier uuid -> attribute it was applied to) */
    private static final Map<UUID, Map<UUID, Attribute>> APPLIED = new HashMap<>();

    private CurioAffixAttributeBridge() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (player == null || player.level().isClientSide() || player.tickCount % 20 != 0) {
            return;
        }
        reconcile(player);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        clear(event.getEntity());
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        clear(event.getOriginal());
    }

    private static void clear(@Nullable Player player) {
        if (player == null) {
            return;
        }
        Map<UUID, Attribute> applied = APPLIED.remove(player.getUUID());
        if (applied == null) {
            return;
        }
        for (Map.Entry<UUID, Attribute> entry : applied.entrySet()) {
            AttributeInstance instance = player.getAttribute(entry.getValue());
            if (instance != null) {
                instance.removeModifier(entry.getKey());
            }
        }
    }

    /** Pure-ish helper: the modifiers a curio should contribute right now. */
    public static List<CurioModifier> desiredModifiers(List<SlotResult> curios) {
        Map<UUID, CurioModifier> desired = new LinkedHashMap<>();
        for (SlotResult result : curios) {
            ItemStack stack = result.stack();
            if (stack.isEmpty()) {
                continue;
            }
            SlotContext context = result.slotContext();
            List<AffixInstance> instances;
            try {
                instances = AffixHelper.streamAffixes(stack).toList();
            } catch (RuntimeException ex) {
                Debug.log("bridge: unreadable affixes on %s: %s", stack, ex);
                continue;
            }
            for (AffixInstance instance : instances) {
                if (!instance.isValid()) {
                    continue;
                }
                Affix affix = instance.affix().get();
                if (!(affix instanceof SchoolAttributeAffix schoolAffix)) {
                    continue;
                }
                Attribute attribute = schoolAffix.attribute();
                LootRarity rarity = instance.rarity().get();
                if (attribute == null || rarity == null) {
                    continue;
                }
                double value = schoolAffix.valueFor(rarity, instance.level());
                if (value == 0.0D) {
                    continue;
                }
                UUID uuid = modifierUuid(context, attribute);
                if (uuid == null) {
                    continue;
                }
                desired.put(uuid, new CurioModifier(uuid, attribute, value, schoolAffix.operation(), context.identifier(), context.index()));
            }
        }
        return List.copyOf(desired.values());
    }

    @Nullable
    private static UUID modifierUuid(SlotContext context, Attribute attribute) {
        ResourceLocation attributeId = net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getKey(attribute);
        if (attributeId == null) {
            return null;
        }
        String key = "irons_apothic/curio_affix/" + context.identifier() + "/" + context.index() + "/" + attributeId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static void reconcile(Player player) {
        List<CurioModifier> desired = desiredModifiers(dev.muon.irons_apothic.util.PlayerGear.curios(player));
        Map<UUID, Attribute> applied = APPLIED.computeIfAbsent(player.getUUID(), key -> new LinkedHashMap<>());

        // Drop modifiers that are no longer wanted.
        for (Iterator<Map.Entry<UUID, Attribute>> it = applied.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Attribute> entry = it.next();
            boolean wanted = desired.stream().anyMatch(modifier -> modifier.uuid().equals(entry.getKey()));
            if (!wanted) {
                AttributeInstance instance = player.getAttribute(entry.getValue());
                if (instance != null) {
                    instance.removeModifier(entry.getKey());
                }
                it.remove();
                Debug.log("bridge: removed stale modifier %s", entry.getKey());
            }
        }

        // Add or refresh the wanted modifiers.
        for (CurioModifier modifier : desired) {
            AttributeInstance instance = player.getAttribute(modifier.attribute());
            if (instance == null) {
                continue;
            }
            AttributeModifier existing = instance.getModifier(modifier.uuid());
            if (existing != null
                    && Double.compare(existing.getAmount(), modifier.value()) == 0
                    && existing.getOperation() == modifier.operation()) {
                applied.put(modifier.uuid(), modifier.attribute());
                continue;
            }
            if (existing != null) {
                instance.removeModifier(modifier.uuid());
            }
            instance.addTransientModifier(new AttributeModifier(
                    modifier.uuid(), SchoolAttributeAffix.MODIFIER_NAME, modifier.value(), modifier.operation()));
            applied.put(modifier.uuid(), modifier.attribute());
            Debug.log("bridge: applied %s %s %s from curio slot %s#%d",
                    net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getKey(modifier.attribute()),
                    modifier.value(), modifier.operation(), modifier.slot(), modifier.index());
        }
    }

    /** One attribute modifier a curio wants to contribute. */
    public record CurioModifier(UUID uuid, Attribute attribute, double value, AttributeModifier.Operation operation,
                                String slot, int index) {
    }
}
