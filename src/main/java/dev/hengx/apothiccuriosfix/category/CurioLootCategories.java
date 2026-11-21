package dev.hengx.apothiccuriosfix.category;

import dev.hengx.apothiccuriosfix.util.Debug;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.ISlotType;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Registers a {@code curios:<slot>} loot category for <b>every</b> curio slot, whether this mod knows the
 * slot in advance or not.
 *
 * <h2>Why this class exists</h2>
 * Apotheosis validates every affix / gem JSON while the data pack reloads: their {@code types} arrays are
 * decoded through {@link LootCategory#SET_CODEC}, which resolves names against {@link LootCategory#BY_ID}. A
 * single unknown name makes {@code stringResolverCodec} fail with {@code Unknown element name:curios:xxx} and
 * the <em>whole file</em> is dropped - no affixes for that slot, forever. That is why a charm could not roll
 * numeric (attribute) affixes and why the log was full of "Failed parsing affixes file" lines.
 *
 * <p>ApothicCurios 1.0.3e tries to fix this from inside a mixin on {@link LootCategory#byId}, but the
 * {@code -optimized} rebuild used by the packs lost the {@code MixinConfigs} manifest attribute, so its mixin
 * never runs. This mod therefore owns the job (and does not need ApothicCurios at runtime).</p>
 *
 * <h2>Three layers, all idempotent</h2>
 * <ol>
 *     <li><b>Mod construction</b> - {@link #register()}: the slot ids Curios' own API reports right now (usually
 *         none yet, the slot registry is data-pack driven) plus {@link #BASE_SLOTS}, the slots Curios and the
 *         common addons ship. This alone already covers everything Apotheosis' own affix files reference.</li>
 *     <li><b>Every data pack reload</b> - {@code CurioLootCategoriesReloadListener}: re-enumerates the API (all
 *         {@code data/curios/slots/*.json} of every pack are loaded by then) and registers whatever is new.</li>
 *     <li><b>{@link LootCategory#byId}</b> - {@code dev.hengx.apothiccuriosfix.mixin.LootCategoryMixin} calls
 *         {@link #ensureFromId(String)} for any still unknown {@code curios:*} name, so even a slot type this
 *         mod has never heard of resolves while the JSON is being parsed. Nothing else can be relied on:
 *         Apotheosis' data pack listeners and Curios' slot loader are ordered by mod load order, so no reload
 *         listener of this mod can guarantee it runs first.</li>
 * </ol>
 *
 * <h2>Idempotency</h2>
 * {@code LootCategory.register} throws {@code IllegalArgumentException("Cannot register a loot category with a
 * duplicate name.")} when the id already exists, so every registration goes through
 * {@code LootCategory.BY_ID.containsKey(id)} first: an existing category (from ApothicCurios, another addon or
 * this mod's earlier pass) is adopted and never overwritten.
 *
 * <h2>Which vanilla slots the categories claim</h2>
 * {@code MAINHAND}/{@code OFFHAND}, never an armor slot. {@code AttributeAffix#addModifiers(...)} emits a
 * modifier only when one of {@code LootCategory#getSlots()} equals the slot it is asked about, so this is what
 * lets {@code CurioAffixAttributeBridge} read the real values (and stops ApothicCurios' fake {@code LEGS} pass
 * from double-applying them). A non-empty array also keeps {@code getSlots()[0]} valid for
 * {@code AdventureEvents#special} (mobs spawned with a random affixed curio), which an empty array would break.
 * The consequence - ApothicAttributes printing a "when in any hand" group for the item - is removed client side
 * by {@code dev.hengx.apothiccuriosfix.util.TooltipRules} plus {@code AttributeTooltipFilter}.
 */
public final class CurioLootCategories {

    /** {@code curios:} namespace shared by every category this mod (and ApothicCurios) registers. */
    public static final String PREFIX = "curios:";

    /**
     * Slots that exist even when no data pack has been read yet: Curios' own eleven ({@code data/curios/slots})
     * plus the addon slots seen in the packs ({@code an_focus} and friends come from Apotheotic Additions,
     * {@code brooch} from Malum, ...). {@link #enumerateCurioSlots()} adds everything else at runtime.
     */
    public static final List<String> BASE_SLOTS = List.of(
            "ring", "necklace", "charm", "belt", "rune", "spellbook",
            "bracelet", "hands", "head", "feet", "body",
            "an_focus", "back", "brooch", "spellstone", "scroll", "talisman");

    /**
     * See the class javadoc: non-empty only so that {@code LootCategory#getSlots()[0]} cannot throw in
     * {@code AdventureEvents#special}. {@code LEGS} is deliberately absent.
     */
    private static final EquipmentSlot[] CLAIMED_SLOTS = { EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND };

    private static final Map<String, TagKey<Item>> TAGS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, LootCategory> REGISTERED = new LinkedHashMap<>();
    /** Slot ids the Curios API reported at least once, kept for the self test / debug output. */
    private static final Set<String> ENUMERATED = new LinkedHashSet<>();

    private static volatile boolean attempted;

    private CurioLootCategories() {
    }

    /**
     * Registers every slot this mod knows about, or adopts the categories that already exist. Safe to call any
     * number of times from any thread.
     */
    public static synchronized void register() {
        registerAll();
    }

    /**
     * One registration pass: base slots + whatever the Curios API currently reports + everything registered
     * before. A repeat call is a no-op ({@code created == 0}).
     *
     * @return how many categories were created by this call
     */
    public static synchronized int registerAll() {
        attempted = true;
        Set<String> wanted = new TreeSet<>(BASE_SLOTS);
        wanted.addAll(enumerateCurioSlots());
        wanted.addAll(REGISTERED.keySet());
        int created = 0;
        int adopted = 0;
        for (String slot : wanted) {
            LootCategory existing = LootCategory.BY_ID.get(PREFIX + slot);
            if (existing != null) {
                REGISTERED.put(slot, existing);
                adopted++;
                continue;
            }
            LootCategory category = create(slot);
            if (category != null) {
                REGISTERED.put(slot, category);
                created++;
            }
        }
        Debug.log("curios loot categories: %d created, %d already registered, %d known, %d in LootCategory.BY_ID%s",
                created, adopted, REGISTERED.size(), LootCategory.BY_ID.size(),
                ENUMERATED.isEmpty() ? "" : ", API slots " + ENUMERATED);
        return created;
    }

    /** Registers {@code curios:<slot>} when it is missing and returns the category. */
    @Nullable
    public static LootCategory ensureSlot(String slot) {
        if (slot == null || slot.isEmpty() || !isValidSlotId(slot)) {
            return null;
        }
        synchronized (CurioLootCategories.class) {
            LootCategory existing = LootCategory.BY_ID.get(PREFIX + slot);
            if (existing != null) {
                REGISTERED.put(slot, existing);
                return existing;
            }
            LootCategory category = create(slot);
            if (category != null) {
                REGISTERED.put(slot, category);
                return category;
            }
            return null;
        }
    }

    /**
     * Called for every {@code LootCategory.byId(name)} lookup (through
     * {@code dev.hengx.apothiccuriosfix.mixin.LootCategoryMixin}). Any {@code curios:<slot>} name that is not
     * registered yet - a slot type from a data pack that has not been read yet, or from a mod this patch has
     * never heard of - is created on the spot so the JSON that contains it still parses.
     */
    public static void ensureFromId(@Nullable String id) {
        if (id == null || !id.startsWith(PREFIX) || id.length() == PREFIX.length()) {
            return;
        }
        if (LootCategory.BY_ID.containsKey(id)) {
            return;
        }
        ensureSlot(id.substring(PREFIX.length()));
    }

    /** The category for {@code curios:<slot>}, or {@code null} when it is not registered. */
    @Nullable
    public static LootCategory category(String slot) {
        LootCategory category = REGISTERED.get(slot);
        return category != null ? category : LootCategory.BY_ID.get(PREFIX + slot);
    }

    /** Every category this mod is responsible for, in registration order. */
    public static Map<String, LootCategory> categories() {
        return Map.copyOf(REGISTERED);
    }

    /** Slot ids the Curios API reported, for diagnostics and the self test. */
    public static Set<String> enumeratedSlots() {
        return Set.copyOf(ENUMERATED);
    }

    /** The {@code curios:<slot>} item tag, used as the primary - and cheapest - detection path. */
    public static TagKey<Item> tag(String slot) {
        return TAGS.computeIfAbsent(slot,
                key -> TagKey.create(Registries.ITEM, new ResourceLocation("curios", key)));
    }

    /** True when {@code category} is one of the {@code curios:*} categories of this mod (or ApothicCurios'). */
    public static boolean isCurioCategory(@Nullable LootCategory category) {
        if (category == null || category.isNone()) {
            return false;
        }
        String name = category.getName();
        return name != null && name.startsWith(PREFIX);
    }

    /** True when the stack resolves to a {@code curios:*} loot category. */
    public static boolean isCurioStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return isCurioCategory(LootCategory.forItem(stack));
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * True when the stack carries one of the {@code curios:<slot>} item tags. Secondary detection used by the
     * tooltip rule: a pack may mark an item as a curio in the tag without any category ever matching it, and
     * {@code LootCategory.forItem} returns the <em>first</em> matching category, which another mod may own.
     */
    public static boolean isCurioTagged(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        for (String slot : REGISTERED.keySet()) {
            try {
                if (stack.is(tag(slot))) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ex) {
                return false;
            }
        }
        // The slot registry may hold slots this mod has not registered yet (first tick after a reload).
        for (String slot : ENUMERATED) {
            try {
                if (stack.is(tag(slot))) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ex) {
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ internals

    /** Creates the category; {@code null} when registration is impossible (duplicate / unexpected error). */
    @Nullable
    private static LootCategory create(String slot) {
        String id = PREFIX + slot;
        if (LootCategory.BY_ID.containsKey(id)) {
            return null;
        }
        try {
            LootCategory category = LootCategory.register(null, id, validator(slot), CLAIMED_SLOTS);
            Debug.log("registered loot category %s (slots=%s)", id, java.util.Arrays.toString(CLAIMED_SLOTS));
            return category;
        } catch (RuntimeException ex) {
            // Never let a registration problem abort mod loading: Apotheosis would then lose every category
            // registered after this one.
            Debug.log("could not register loot category %s: %s", id, ex);
            return null;
        }
    }

    /** Slot ids currently known to Curios itself ({@code ISlotHelper#getSlotTypes} via {@code CuriosApi}). */
    private static Set<String> enumerateCurioSlots() {
        Set<String> slots = new LinkedHashSet<>();
        try {
            Map<String, ISlotType> reported = CuriosApi.getSlots();
            if (reported != null) {
                for (Map.Entry<String, ISlotType> entry : reported.entrySet()) {
                    String id = entry.getKey();
                    if ((id == null || id.isEmpty()) && entry.getValue() != null) {
                        id = entry.getValue().getIdentifier();
                    }
                    if (id != null && !id.isEmpty() && isValidSlotId(id)) {
                        slots.add(id);
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            // Curios is not installed, or its slot helper has not been installed yet (the API is data-pack
            // driven). Both are normal during mod construction.
            Debug.log("Curios API slot enumeration not available yet: %s", ex);
        }
        ENUMERATED.addAll(slots);
        return slots;
    }

    /** Guards against junk ids coming from JSON or from a broken addon. */
    private static boolean isValidSlotId(String slot) {
        if (slot.length() > 64) {
            return false;
        }
        for (int i = 0; i < slot.length(); i++) {
            char c = slot.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the validator for one curio slot: the {@code curios:<slot>} item tag first (cheap, addon
     * friendly, and what a data pack would use to mark a custom trinket), then an id based fallback for
     * addons that forgot the tag, and finally Curios' own capability check.
     */
    private static Predicate<ItemStack> validator(String slot) {
        TagKey<Item> tag = tag(slot);
        // SlotContext is an immutable record; a null entity is what ApothicCurios itself passes and is fine
        // for canEquip(), which only looks at the stack and the slot identifier.
        SlotContext context = new SlotContext(slot, null, 0, false, false);
        return stack -> {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            try {
                if (stack.is(tag)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ex) {
                Debug.log("tag lookup failed for %s: %s", stack, ex);
            }
            if (matchesSlotId(stack, slot)) {
                return true;
            }
            try {
                return CuriosApi.isStackValid(context, stack);
            } catch (RuntimeException | LinkageError ex) {
                // Curios may not be fully initialised while the data pack loads; treat it as "not a curio".
                return false;
            }
        };
    }

    /**
     * Id based fallback, restricted to non-vanilla namespaces so that {@code minecraft:player_head} is not
     * suddenly a curio. Matches {@code foo_charm}, {@code charm_foo} and {@code foo_charm_bar}.
     */
    public static boolean matchesSlotId(ItemStack stack, String slot) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || "minecraft".equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath();
        return path.equals(slot) || path.endsWith("_" + slot) || path.startsWith(slot + "_") || path.contains("_" + slot + "_");
    }
}
