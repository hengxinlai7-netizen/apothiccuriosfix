package dev.hengx.apothiccuriosfix.event;

import dev.hengx.apothiccuriosfix.ApothicCuriosFix;
import dev.hengx.apothiccuriosfix.gem.GemCurioSupport;
import dev.hengx.apothiccuriosfix.util.Debug;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.socket.SocketHelper;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemInstance;
import dev.shadowsoffire.placebo.reload.DynamicHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Grants the attributes of Apotheosis affixes that sit on <em>curio</em> items to their wearer.
 *
 * <p>Apotheosis applies affix attribute modifiers through Forge's {@code ItemAttributeModifierEvent}, which
 * is only fired for the vanilla equipment slots. Curios 5.14.1 never fires that event for the slots it owns,
 * so an attribute affix on a charm had no effect on the player at all - it only produced the
 * "when on feet / on legs / on chest / on head" tooltip groups. Because there is no event to hook, this
 * bridge polls: once every 20 ticks it recomputes the desired modifier set from the player's curio slots and
 * adds / refreshes / removes the corresponding {@link AttributeModifier}s.</p>
 *
 * <p>Details that matter:</p>
 * <ul>
 *     <li>The modifiers are <em>transient</em> (never written to NBT) and carry a UUID derived from
 *         {@code player + curio slot + slot index + affix id + attribute + operation}, so the same affix on
 *         two rings yields two distinct modifiers and a re-poll always resolves to the same UUID.</li>
 *     <li>A UUID is added at most once: an already present modifier with the same amount and operation is
 *         left untouched instead of being re-added.</li>
 *     <li>Anything recorded in the applied map that is no longer desired (item unequipped, affix rerolled,
 *         rarity changed, player changed dimension / died) is removed again.</li>
 *     <li>The modifier values come from {@link AffixInstance#addModifiers(EquipmentSlot, BiConsumer)}, i.e.
 *         from Apotheosis' own per-rarity/per-level value resolution - so {@code attribute} affixes, addon
 *         affix types and any future subclass all work without this mod knowing about them. The slot passed
 *         in is {@link EquipmentSlot#MAINHAND} because it is one of the two slots
 *         {@link dev.hengx.apothiccuriosfix.category.CurioLootCategories} claims; that is what makes
 *         {@code AttributeAffix#addModifiers} emit anything at all.</li>
 * </ul>
 */
public final class CurioAffixAttributeBridge {

    /** Name shown by attribute-source tooltips for every modifier this bridge adds. */
    public static final String MODIFIER_NAME = ApothicCuriosFix.MODID + ":curio_affix";

    /** entity uuid -> (modifier uuid -> attribute the modifier was applied to) */
    private static final Map<UUID, Map<UUID, Attribute>> APPLIED = new HashMap<>();

    private CurioAffixAttributeBridge() {
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (player == null || player.level().isClientSide() || player.tickCount % 20 != 0) {
            return;
        }
        try {
            reconcile(player);
        } catch (RuntimeException | LinkageError ex) {
            // A broken curio stack must never break the player tick.
            Debug.log("bridge: reconcile failed for %s: %s", player, ex);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        clear(event.getEntity());
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        // The original entity is gone; drop its bookkeeping (the modifiers die with it) and let the new
        // player be reconciled on its next tick.
        APPLIED.remove(event.getOriginal().getUUID());
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
        Debug.log("bridge: cleared %d modifiers on logout", applied.size());
    }

    // ------------------------------------------------------------------ desired set

    /** One equipped curio: its slot identifier, its index inside that slot and the stack itself. */
    public record CurioStack(String slot, int index, ItemStack stack) {
    }

    /** One attribute modifier a curio wants to contribute. */
    public record CurioModifier(UUID uuid, Attribute attribute, double value,
                                AttributeModifier.Operation operation, String slot, int index,
                                ResourceLocation affix) {
    }

    /** Pulls every equipped curio of the entity (may be empty when Curios is unavailable). */
    public static List<CurioStack> curios(LivingEntity entity) {
        try {
            return CuriosApi.getCuriosInventory(entity)
                    .map(handler -> handler.findCurios(stack -> !stack.isEmpty()).stream()
                            .map(CurioAffixAttributeBridge::toCurio)
                            .toList())
                    .orElse(List.of());
        } catch (RuntimeException | LinkageError ex) {
            Debug.log("bridge: could not read curios of %s: %s", entity, ex);
            return List.of();
        }
    }

    private static CurioStack toCurio(SlotResult result) {
        var context = result.slotContext();
        return new CurioStack(context.identifier(), context.index(), result.stack());
    }

    /**
     * The modifiers a curio should contribute right now. Never touches the player: the same call is used by
     * the self test to exercise the whole value-resolution pipeline.
     */
    public static List<CurioModifier> desiredModifiers(LivingEntity owner, List<CurioStack> curios) {
        Map<UUID, CurioModifier> desired = new LinkedHashMap<>();
        for (CurioStack curio : curios) {
            ItemStack stack = curio.stack();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Map<DynamicHolder<? extends Affix>, AffixInstance> affixes;
            try {
                affixes = AffixHelper.getAffixes(stack);
            } catch (RuntimeException | LinkageError ex) {
                Debug.log("bridge: unreadable affixes on %s: %s", stack, ex);
                continue;
            }
            for (Map.Entry<DynamicHolder<? extends Affix>, AffixInstance> entry : affixes.entrySet()) {
                AffixInstance instance = entry.getValue();
                if (instance == null || !instance.isValid()) {
                    continue;
                }
                DynamicHolder<? extends Affix> holder = entry.getKey();
                if (holder == null || holder.getId() == null) {
                    continue;
                }
                ResourceLocation affixId = holder.getId();
                instance.addModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                    if (attribute == null || modifier == null || modifier.getAmount() == 0.0D) {
                        return;
                    }
                    UUID uuid = modifierUuid(owner, curio, affixId, attribute, modifier.getOperation());
                    if (uuid == null) {
                        return;
                    }
                    desired.put(uuid, new CurioModifier(uuid, attribute, modifier.getAmount(),
                            modifier.getOperation(), curio.slot(), curio.index(), affixId));
                });
            }

            // 饰品上的神化宝石(白名单见 GemCurioSupport):和词缀走同一条结算管线。
            // AP 只在原版装备槽上应用宝石属性,Curios 的槽位不在其中,所以这里补上。
            // GemInstance 的类目就是镶嵌时记下的 curios:<slot>,而 GemMixin 让 getBonus 认这个类目,
            // 所以 isValid()/addModifiers() 都能正常工作。
            try {
                LootCategory gemCategory = LootCategory.forItem(stack);
                if (GemCurioSupport.isCurioCategory(gemCategory)) {
                    for (GemInstance gem : SocketHelper.getGems(stack)) {
                        if (gem == null || !gem.isValid()) {
                            continue;
                        }
                        ResourceLocation gemId = gem.gem() == null ? null : gem.gem().getId();
                        if (gemId == null) {
                            continue;
                        }
                        gem.addModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                            if (attribute == null || modifier == null || modifier.getAmount() == 0.0D) {
                                return;
                            }
                            UUID uuid = modifierUuid(owner, curio, gemId, attribute, modifier.getOperation());
                            if (uuid == null) {
                                return;
                            }
                            desired.put(uuid, new CurioModifier(uuid, attribute, modifier.getAmount(),
                                    modifier.getOperation(), curio.slot(), curio.index(), gemId));
                        });
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                Debug.log("bridge: unreadable gems on %s: %s", stack, ex);
            }
        }
        return List.copyOf(desired.values());
    }

    /** Stable UUID for one (player, curio slot, affix, attribute, operation) combination. */
    @Nullable
    private static UUID modifierUuid(LivingEntity owner, CurioStack curio, ResourceLocation affixId,
                                     Attribute attribute, AttributeModifier.Operation operation) {
        ResourceLocation attributeId;
        try {
            attributeId = ForgeRegistries.ATTRIBUTES.getKey(attribute);
        } catch (RuntimeException ex) {
            return null;
        }
        if (attributeId == null) {
            return null;
        }
        String key = ApothicCuriosFix.MODID + "/curio_affix/"
                + owner.getUUID() + "/"
                + curio.slot() + "/" + curio.index() + "/"
                + affixId + "/" + attributeId + "/" + operation.ordinal();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ reconcile

    /** Recomputes the curios of the player and reconciles the player's attribute modifiers with them. */
    public static void reconcile(Player player) {
        List<CurioModifier> desired = desiredModifiers(player, curios(player));
        Map<UUID, Attribute> applied = APPLIED.computeIfAbsent(player.getUUID(), key -> new LinkedHashMap<>());
        applyDiff(player, desired, applied);
    }

    /**
     * Applies {@code desired} to {@code entity} and removes everything recorded in {@code applied} that is no
     * longer wanted.
     *
     * @return the number of modifiers that are applied to the entity afterwards
     */
    public static int applyDiff(LivingEntity entity, List<CurioModifier> desired, Map<UUID, Attribute> applied) {
        // 1. Remove modifiers we added earlier that are no longer wanted.
        for (Iterator<Map.Entry<UUID, Attribute>> it = applied.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Attribute> entry = it.next();
            boolean wanted = false;
            for (CurioModifier modifier : desired) {
                if (modifier.uuid().equals(entry.getKey())) {
                    wanted = true;
                    break;
                }
            }
            if (!wanted) {
                AttributeInstance instance = entity.getAttribute(entry.getValue());
                if (instance != null && instance.getModifier(entry.getKey()) != null) {
                    instance.removeModifier(entry.getKey());
                }
                it.remove();
                Debug.log("bridge: removed stale modifier %s", entry.getKey());
            }
        }

        // 2. Add or refresh the wanted modifiers, never twice.
        for (CurioModifier modifier : desired) {
            AttributeInstance instance = entity.getAttribute(modifier.attribute());
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
                    modifier.uuid(), MODIFIER_NAME, modifier.value(), modifier.operation()));
            applied.put(modifier.uuid(), modifier.attribute());
            Debug.log("bridge: applied %s %s %s from curio slot %s#%d (affix %s)",
                    ForgeRegistries.ATTRIBUTES.getKey(modifier.attribute()), modifier.value(),
                    modifier.operation(), modifier.slot(), modifier.index(), modifier.affix());
        }
        return applied.size();
    }

    /** Test/debug helper: how many modifiers with this UUID the entity currently carries. */
    public static int countModifiers(LivingEntity entity, UUID uuid) {
        int count = 0;
        for (Attribute attribute : ForgeRegistries.ATTRIBUTES.getValues()) {
            AttributeInstance instance = entity.getAttribute(attribute);
            if (instance != null && instance.getModifier(uuid) != null) {
                count++;
            }
        }
        return count;
    }
}
