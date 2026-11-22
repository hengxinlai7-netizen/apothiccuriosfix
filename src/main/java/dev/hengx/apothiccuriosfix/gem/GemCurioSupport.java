package dev.hengx.apothiccuriosfix.gem;

import dev.hengx.apothiccuriosfix.mixin.AttributeBonusAccessor;
import dev.hengx.apothiccuriosfix.mixin.GemBonusAccessor;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.Gem;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemRegistry;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.bonus.GemBonus;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 让<b>原有的</b>神化宝石能镶到饰品上 —— 不注册任何新宝石。
 *
 * <h2>问题</h2>
 * 神化判定"这颗宝石能不能镶进这件装备"只有一条:
 * <pre>
 * Gem#isValidIn(gemStack, itemStack, rarity):
 *     LootCategory cat = LootCategory.forItem(itemStack);
 *     return !cat.isNone() &amp;&amp; bonusMap.containsKey(cat) &amp;&amp; bonusMap.get(cat).supports(rarity);
 * </pre>
 * 宝石的 {@code bonusMap} 是在数据包加载时按 JSON 里每个 bonus 的 {@code gem_class.types} 建的,而 AP 自带的
 * 宝石只写了 {@code sword / chestplate / pickaxe ...} 这些原版类目,<b>没有任何宝石带 curios:* 类目</b>,
 * 所以饰品永远 {@code isValidIn == false} —— 即使本模组已经给每个饰品槽注册了
 * {@code curios:<slot>} 类目({@link dev.hengx.apothiccuriosfix.category.CurioLootCategories})。
 *
 * <h2>做法</h2>
 * 不新增宝石、不改任何 JSON:只挑几颗<b>原有</b>宝石,在 {@code Gem} 的两个方法上补一层回退
 * ({@code mixin.GemMixin}):
 * <ul>
 *     <li>{@code isValidIn} —— 类目是 {@code curios:*} 且宝石在白名单里 ⇒ 视为可镶;</li>
 *     <li>{@code getBonus} —— 类目是 {@code curios:*} 且宝石在白名单里 ⇒ 返回下面挑出来的那个 bonus。</li>
 * </ul>
 * 于是镶进饰品的宝石"有属性"而不只是占个孔:{@code GemInstance.isValid()} 与
 * {@code GemInstance.addModifiers(...)} 都是走 {@code getBonus},本模组的
 * {@code CurioAffixAttributeBridge} 再把它们变成玩家身上的属性修饰符。
 *
 * <h2>挑哪些宝石、挑哪个 bonus</h2>
 * 只选"戴在身上也说得通"的宝石,并且每颗都映射到它自己的<b>护甲类属性 bonus</b>
 * (优先 {@code chestplate/leggings},其次 {@code boots}),因为其它 bonus(附魔、药水、伤害减免……)
 * 需要额外的运行时钩子,本模组只结算属性这一类。
 *
 * <table>
 *     <tr><th>宝石</th><th>用作饰品时的效果</th></tr>
 *     <tr><td>{@code apotheosis:core/splendor}</td><td>幸运</td></tr>
 *     <tr><td>{@code apotheosis:core/guardian}</td><td>护甲</td></tr>
 *     <tr><td>{@code apotheosis:core/brawlers}</td><td>最大生命</td></tr>
 *     <tr><td>{@code apotheosis:core/ballast}</td><td>击退抗性</td></tr>
 *     <tr><td>{@code apotheosis:core/tyrannical}</td><td>护甲韧性</td></tr>
 *     <tr><td>{@code apotheosis:core/lightning}</td><td>移动速度</td></tr>
 * </table>
 */
public final class GemCurioSupport {

    /**
     * 允许镶到饰品上的**原有**宝石(AP 7.4.8 自带,一个都没有被修改)。
     *
     * <p>前 6 颗是"戴身上说得通"的防御/功能向:辉煌(幸运)、护卫(护甲)、斗争(最大生命)、
     * 稳定(击退抗性)、暴君(护甲韧性)、闪电(移动速度)。</p>
     *
     * <p>后面 9 颗是**增伤向**的(用户 2026/10/24 追加"武士宝石跟战士宝石那些涉及增伤的也弄进去"):
     * 武士(暴击率)、战斗(投射物伤害)、战神(暴击伤害)、突破(穿甲)、阳炎(火焰伤害)、
     * 月华(冰霜伤害)、炽狱(火焰伤害)、嗜血领主(吸血)、冰雪女王(冰霜伤害)。</p>
     *
     * <p>没进名单的两颗,原因写在 {@link #pick} 上面:法师克星/王室的招牌效果不是属性
     * (法师克星是"对法师增伤"的专用 bonus,王室是 all_stats),靠本模组这条属性管线拿不到。</p>
     */
    public static final List<ResourceLocation> ALLOWED_GEMS = List.of(
            // 防御 / 功能
            new ResourceLocation("apotheosis", "core/splendor"),
            new ResourceLocation("apotheosis", "core/guardian"),
            new ResourceLocation("apotheosis", "core/brawlers"),
            new ResourceLocation("apotheosis", "core/ballast"),
            new ResourceLocation("apotheosis", "core/tyrannical"),
            new ResourceLocation("apotheosis", "core/lightning"),
            // 增伤
            new ResourceLocation("apotheosis", "core/samurai"),
            new ResourceLocation("apotheosis", "core/combatant"),
            new ResourceLocation("apotheosis", "core/warlord"),
            new ResourceLocation("apotheosis", "core/breach"),
            new ResourceLocation("apotheosis", "core/solar"),
            new ResourceLocation("apotheosis", "core/lunar"),
            new ResourceLocation("apotheosis", "the_nether/inferno"),
            new ResourceLocation("apotheosis", "the_nether/blood_lord"),
            new ResourceLocation("apotheosis", "twilight/queen"));

    /**
     * 其中属于"增伤组"的宝石:选 bonus 时优先挑伤害/暴击类属性,而不是它同名的移速/生命 bonus。
     * 例:武士宝石既有 {@code crit_chance} 也有 {@code movement_speed},战神宝石既有 {@code crit_damage}
     * 也有 {@code max_health} —— 不加这一层,它们会变成移速宝石 / 生命宝石。
     */
    public static final List<ResourceLocation> DAMAGE_GEMS = List.of(
            new ResourceLocation("apotheosis", "core/samurai"),
            new ResourceLocation("apotheosis", "core/combatant"),
            new ResourceLocation("apotheosis", "core/warlord"),
            new ResourceLocation("apotheosis", "core/breach"),
            new ResourceLocation("apotheosis", "core/solar"),
            new ResourceLocation("apotheosis", "core/lunar"),
            new ResourceLocation("apotheosis", "the_nether/inferno"),
            new ResourceLocation("apotheosis", "the_nether/blood_lord"),
            new ResourceLocation("apotheosis", "twilight/queen"));

    /**
     * "算增伤"的属性白名单(命中就优先)。名字都是 AP 7.4.8 + ApothicAttributes 实际注册的:
     * 暴击率/暴击伤害、穿甲、护甲削减、保护穿透、投射物伤害、火焰/冰霜伤害、吸血、当前生命伤害、
     * 攻击力与攻击速度。
     */
    public static final Set<String> DAMAGE_ATTRIBUTES = Set.of(
            "attributeslib:crit_chance",
            "attributeslib:crit_damage",
            "attributeslib:armor_pierce",
            "attributeslib:armor_shred",
            "attributeslib:prot_pierce",
            "attributeslib:arrow_damage",
            "attributeslib:fire_damage",
            "attributeslib:cold_damage",
            "attributeslib:life_steal",
            "attributeslib:current_hp_damage",
            "minecraft:generic.attack_damage",
            "minecraft:generic.attack_speed");

    private GemCurioSupport() {
    }

    /** 类目是不是本模组给饰品注册的 {@code curios:<slot>}。 */
    public static boolean isCurioCategory(LootCategory category) {
        return category != null && !category.isNone() && category.getName().startsWith("curios:");
    }

    /**
     * 与 {@code LootCategory.forItem} <b>无关</b>的饰品判定:直接看物品有没有命中某个
     * {@code curios:<slot>} 的 tag。
     *
     * <p>为什么要另开一个判据:{@code modules.curiosReforgeable} 默认 {@code false},
     * {@link dev.hengx.apothiccuriosfix.mixin.MmTetraLootCategoryMixin} 会把 {@code forItem} 的结果
     * 强制抹成 {@link LootCategory#NONE}(3.1.0 的策略:饰品不参与神化生成)。
     * 而宝石链路过去也拿 {@code forItem} 当唯一信号,于是跟着一起失效 ——
     * 这就是"宝石镶得上、但属性一条都不加"的直接原因。</p>
     */
    public static boolean isCurioItem(ItemStack stack) {
        return dev.hengx.apothiccuriosfix.category.CurioLootCategories.isCurioTagged(stack);
    }

    /** 这颗宝石是否在允许列表里(按注册表实例反查,不依赖任何名字推断)。 */
    public static boolean isAllowed(Gem gem) {
        if (gem == null) {
            return false;
        }
        for (ResourceLocation id : ALLOWED_GEMS) {
            try {
                if (GemRegistry.INSTANCE.getValue(id) == gem) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // 注册表还没就绪:当作不允许
            }
        }
        return false;
    }

    /**
     * 从一颗宝石的 bonus 列表里挑出"挂身上"用的那个:
     * 先护甲类目(胸/腿)的、能产出属性修饰符的 bonus;再靴子的;再任意能产出修饰符的;最后兜底第一个。
     *
     * <p>"能产出修饰符"是<b>按方法重写判定</b>,不是写死类型:{@code AttributeBonus}、{@code MultiAttrBonus}、
     * {@code AllStatsBonus} 以及以后 addon 自己写的子类只要重写了 {@code addModifiers} 都算,
     * 这样每一颗宝石都拿到它自己最"像戴身上"的那一条,而不是本模组去解释它的数值。</p>
     *
     * <p>增伤类宝石(武士/战斗/战神/突破/阳炎/月华/炽狱/嗜血领主/冰雪女王)没有护甲类 bonus,
     * 于是落到"任意能产出修饰符的"那一档 —— 对它们来说正好就是那条伤害/暴击属性。</p>
     */
    public static GemBonus pick(Gem gem, List<GemBonus> bonuses, LootRarity rarity) {
        if (bonuses == null || bonuses.isEmpty()) {
            return null;
        }
        if (isDamageGem(gem)) {
            GemBonus damage = pickDamage(bonuses, rarity);
            if (damage != null) {
                return damage;
            }
        }
        GemBonus armor = null;
        GemBonus boots = null;
        GemBonus anyEmitter = null;
        GemBonus first = null;
        for (GemBonus bonus : bonuses) {
            if (bonus == null) {
                continue;
            }
            if (first == null) {
                first = bonus;
            }
            boolean emits = emitsModifiers(bonus);
            if (emits && anyEmitter == null) {
                anyEmitter = bonus;
            }
            if (!emits || (rarity != null && !bonus.supports(rarity))) {
                continue;
            }
            Set<LootCategory> types = typesOf(bonus);
            if (types.contains(LootCategory.CHESTPLATE) || types.contains(LootCategory.LEGGINGS)) {
                if (armor == null) {
                    armor = bonus;
                }
            } else if (types.contains(LootCategory.BOOTS) && boots == null) {
                boots = bonus;
            }
        }
        if (armor != null) {
            return armor;
        }
        if (boots != null) {
            return boots;
        }
        return anyEmitter != null ? anyEmitter : first;
    }

    /**
     * 这个 bonus 会不会真的产出属性修饰符(即它重写了 {@code GemBonus#addModifiers})。
     * 只看"重写与否",不关心具体是哪一类 —— 这是最稳的判据,addon 的 bonus 也自动适用。
     */
    public static boolean emitsModifiers(GemBonus bonus) {
        if (bonus == null) {
            return false;
        }
        Boolean cached = EMITS.get(bonus.getClass());
        if (cached != null) {
            return cached;
        }
        boolean emits;
        try {
            var method = bonus.getClass().getMethod("addModifiers", net.minecraft.world.item.ItemStack.class,
                    LootRarity.class, java.util.function.BiConsumer.class);
            emits = method.getDeclaringClass() != GemBonus.class;
        } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
            emits = false;
        }
        EMITS.put(bonus.getClass(), emits);
        return emits;
    }

    private static final Map<Class<?>, Boolean> EMITS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 增伤组:挑第一条"属性属于增伤列表"的 bonus。 */
    private static GemBonus pickDamage(List<GemBonus> bonuses, LootRarity rarity) {
        for (GemBonus bonus : bonuses) {
            if (bonus == null || !emitsModifiers(bonus)) {
                continue;
            }
            if (rarity != null && !bonus.supports(rarity)) {
                continue;
            }
            ResourceLocation attribute = attributeOf(bonus);
            if (attribute != null && DAMAGE_ATTRIBUTES.contains(attribute.toString())) {
                return bonus;
            }
        }
        return null;
    }

    /** 这条 bonus 加成的属性 id(不是 {@code AttributeBonus} 就返回 null)。 */
    @Nullable
    private static ResourceLocation attributeOf(GemBonus bonus) {
        try {
            if (bonus instanceof AttributeBonusAccessor accessor) {
                var attribute = accessor.apcfix$attribute();
                return attribute == null ? null : ForgeRegistries.ATTRIBUTES.getKey(attribute);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 读不到就当不知道
        }
        return null;
    }

    /** 这颗宝石是否属于"增伤组"。 */
    public static boolean isDamageGem(Gem gem) {
        if (gem == null) {
            return false;
        }
        for (ResourceLocation id : DAMAGE_GEMS) {
            try {
                if (GemRegistry.INSTANCE.getValue(id) == gem) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // 注册表未就绪
            }
        }
        return false;
    }

    private static Set<LootCategory> typesOf(GemBonus bonus) {
        try {
            var gemClass = ((GemBonusAccessor) bonus).apcfix$gemClass();
            return gemClass == null ? Set.of() : gemClass.types();
        } catch (RuntimeException | LinkageError ignored) {
            return Set.of();
        }
    }
}
