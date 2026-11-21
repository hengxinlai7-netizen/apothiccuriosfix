package dev.hengx.apothiccuriosfix;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * 3.0.0:统一配置文件 —— 各模块可单独开关。
 *
 * <p>生成的配置文件位于 {@code config/apothiccuriosfix-common.toml}。</p>
 *
 * <h2>模块划分</h2>
 * <ul>
 *   <li><b>神化饰品</b>({@code modules.curioFixEnabled}) —— {@code curios:*} 类目注册、
 *       饰品词条属性桥、tooltip 过滤;</li>
 *   <li><b>神化铁魔法</b>({@code modules.ironSpellbooksEnabled}) —— {@code irons_apothic:*} 词条
 *       (法力消耗 / 学派属性 / 节奏打击 / 天削 / 弱点破坏)与相关事件;</li>
 *   <li><b>类目补注册</b>({@code modules.extraLootCategoriesEnabled}) —— {@code staff} /
 *       {@code spellbook} / {@code light_weapon};</li>
 *   <li><b>Tetra 兼容</b>({@code modules.tetraCompatEnabled}) —— MMT / 铁魔法法杖 /
 *       tetra_spell_book 的类目映射;</li>
 *   <li><b>属性伤害独立结算</b>({@code modules.independentAttrDamageEnabled});</li>
 *   <li><b>近战闸门放开</b>({@code modules.meleeToAllDamageEnabled}) —— 旧方案,
 *       默认关闭,由上面的独立结算取代。</li>
 * </ul>
 *
 * <h2>词条初始等级</h2>
 * <p>{@code affixLevel.affixLevelByRarityEnabled} <b>默认 false</b>:
 * 按品质给词条设置初始强化等级(白0/绿1/蓝2/紫3/橙4/远古5),
 * 需要时再打开。</p>
 */
public final class AcfConfig {

    public static final ForgeConfigSpec SPEC;

    // ---- 运行模式 ----
    /** {@code full} = 饰品+铁魔法;{@code iron_only} = 只保留神化铁魔法部分。 */
    public static final ForgeConfigSpec.ConfigValue<String> PROFILE;

    // ---- 模块开关 ----
    public static final ForgeConfigSpec.BooleanValue CURIO_FIX_ENABLED;
    public static final ForgeConfigSpec.BooleanValue CURIOS_REFORGEABLE;
    public static final ForgeConfigSpec.BooleanValue IRON_SPELLBOOKS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue EXTRA_LOOT_CATEGORIES_ENABLED;
    public static final ForgeConfigSpec.BooleanValue TETRA_COMPAT_ENABLED;
    public static final ForgeConfigSpec.BooleanValue INDEPENDENT_ATTR_DAMAGE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue MELEE_TO_ALL_DAMAGE_ENABLED;

    // ---- 词条初始等级 ----
    public static final ForgeConfigSpec.BooleanValue AFFIX_LEVEL_BY_RARITY_ENABLED;

    // ---- 单个词条开关 ----
    public static final ForgeConfigSpec.BooleanValue RHYTHM_STRIKE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue HEAVEN_SEVER_ENABLED;
    public static final ForgeConfigSpec.BooleanValue WEAKNESS_BREAKER_ENABLED;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("运行模式:full = 饰品 + 铁魔法;iron_only = 只保留神化铁魔法部分(饰品模块强制关闭,",
                "Tetra 兼容只映射法杖与魔法书)。").push("profile");
        PROFILE = b
            .comment("full / iron_only。默认 full。")
            .define("mode", "full", o -> o instanceof String s
                && ("full".equalsIgnoreCase(s) || "iron_only".equalsIgnoreCase(s)));
        b.pop();

        b.comment("模块开关 —— 关闭后对应功能完全不生效,不用卸载模组").push("modules");

        CURIOS_REFORGEABLE = b
            .comment("饰品能不能被神化(进重铸台、出词条与宝石孔)。默认 false = 饰品不吃神化。")
            .define("curiosReforgeable", false);

        CURIO_FIX_ENABLED = b
            .comment("神化饰品部分:curios:* 类目注册、饰品词条属性同步、tooltip 过滤。默认开启。")
            .define("curioFixEnabled", true);

        IRON_SPELLBOOKS_ENABLED = b
            .comment("神化铁魔法部分:irons_apothic:* 词条与事件。没有安装 Iron's Spellbooks 时自动跳过。默认开启。")
            .define("ironSpellbooksEnabled", true);

        EXTRA_LOOT_CATEGORIES_ENABLED = b
            .comment("补注册 staff / spellbook / light_weapon 类目。关闭后这些类型的词条 JSON 会解析失败。默认开启。")
            .define("extraLootCategoriesEnabled", true);

        TETRA_COMPAT_ENABLED = b
            .comment("Tetra 系物品重铸兼容(MMT 法杖/巫法杖/弓、铁魔法法杖、tetra_spell_book 魔法书)。默认开启。")
            .define("tetraCompatEnabled", true);

        INDEPENDENT_ATTR_DAMAGE_ENABLED = b
            .comment("属性伤害独立结算:玩家造成伤害后额外补一段火/冰伤害,法术也能吃到。默认开启。")
            .define("independentAttrDamageEnabled", true);

        MELEE_TO_ALL_DAMAGE_ENABLED = b
            .comment("旧方案:放开神化 meleeDamageAttributes 的物理伤害闸门。已被独立结算取代,默认关闭。")
            .define("meleeToAllDamageEnabled", false);

        b.pop();

        b.comment("词条初始强化等级").push("affixLevel");
        AFFIX_LEVEL_BY_RARITY_ENABLED = b
            .comment("按品质给重铸/生成的词条设置初始等级(白0 绿1 蓝2 紫3 橙4 远古5)。默认关闭。")
            .define("affixLevelByRarityEnabled", false);
        b.pop();

        b.comment("单个技能词条开关").push("affixes");
        RHYTHM_STRIKE_ENABLED = b.comment("节奏打击。默认开启。").define("rhythmStrikeEnabled", true);
        HEAVEN_SEVER_ENABLED = b.comment("天削。默认开启。").define("heavenSeverEnabled", true);
        WEAKNESS_BREAKER_ENABLED = b.comment("弱点破坏。默认开启。").define("weaknessBreakerEnabled", true);
        b.pop();

        SPEC = b.build();
    }

    private AcfConfig() {
    }

    /** 在模组构造里调用一次。 */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "apothiccuriosfix-common.toml");
    }

    // ---- 读取帮助:配置未加载时一律回落到安全默认值 ----

    /** 是否只保留铁魔法部分。 */
    public static boolean curiosReforgeable() {
        try { return CURIOS_REFORGEABLE.get(); } catch (RuntimeException | LinkageError e) { return false; }
    }

    public static boolean ironOnly() {
        try {
            return "iron_only".equalsIgnoreCase(PROFILE.get());
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static boolean curioFix() {
        return get(CURIO_FIX_ENABLED, true);
    }

    public static boolean ironSpellbooks() {
        return get(IRON_SPELLBOOKS_ENABLED, true);
    }

    public static boolean extraLootCategories() {
        return get(EXTRA_LOOT_CATEGORIES_ENABLED, true);
    }

    public static boolean tetraCompat() {
        return get(TETRA_COMPAT_ENABLED, true);
    }

    public static boolean independentAttrDamage() {
        return get(INDEPENDENT_ATTR_DAMAGE_ENABLED, true);
    }

    public static boolean meleeToAllDamage() {
        return get(MELEE_TO_ALL_DAMAGE_ENABLED, false);
    }

    public static boolean affixLevelByRarity() {
        return get(AFFIX_LEVEL_BY_RARITY_ENABLED, false);
    }

    public static boolean rhythmStrike() {
        return get(RHYTHM_STRIKE_ENABLED, true);
    }

    public static boolean heavenSever() {
        return get(HEAVEN_SEVER_ENABLED, true);
    }

    public static boolean weaknessBreaker() {
        return get(WEAKNESS_BREAKER_ENABLED, true);
    }

    private static boolean get(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        }
        catch (RuntimeException | LinkageError e) {
            return fallback;
        }
    }
}
