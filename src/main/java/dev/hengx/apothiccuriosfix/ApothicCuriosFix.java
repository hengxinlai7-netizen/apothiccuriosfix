package dev.hengx.apothiccuriosfix;

import com.mojang.logging.LogUtils;
import dev.hengx.apothiccuriosfix.category.CurioLootCategories;
import dev.hengx.apothiccuriosfix.command.DiagCommand;
import dev.hengx.apothiccuriosfix.event.CurioAffixAttributeBridge;
import dev.hengx.apothiccuriosfix.event.CurioSlotReloadHandler;
import dev.hengx.apothiccuriosfix.selftest.ClientSelfTest;
import dev.hengx.apothiccuriosfix.selftest.SelfTest;
import dev.hengx.apothiccuriosfix.util.AttributeTooltipFilter;
import dev.hengx.apothiccuriosfix.util.Debug;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * 神化饰品修复 / ApothicCurios Fix.
 *
 * <p>Standalone patch mod with three jobs, all about <em>curio</em> items (rings, amulets, charms, ...) on
 * 1.20.1 Apotheosis 7.4.8:</p>
 *
 * <ol>
 *     <li><b>Make every {@code curios:<slot>} loot category resolvable while the data packs load.</b> Affix and
 *         gem JSON is validated during the reload; an unknown {@code types} entry drops the whole file
 *         ({@code Unknown element name:curios:an_focus}), which is why a charm had no numeric affixes at all.
 *         {@link CurioLootCategories} registers the slots Curios' API reports plus a base set, re-registers on
 *         every reload, and {@code mixin.LootCategoryMixin} creates any still unknown {@code curios:*} name from
 *         inside {@code LootCategory.byId} - the exact method the codec uses - so the ordering of reload
 *         listeners cannot break it. Registration is idempotent: an existing id is adopted, never replaced.</li>
 *     <li><b>Apply curio affix attributes through the Curios slots.</b> Curios 5.14.1 never fires Forge's
 *         {@code ItemAttributeModifierEvent} for curio slots, so attribute affixes on a charm only ever
 *         produced a meaningless vanilla-slot tooltip. The categories registered here claim
 *         {@code MAINHAND}/{@code OFFHAND} (never {@code LEGS}, so ApothicCurios' fake pass stays empty) and
 *         {@link CurioAffixAttributeBridge} keeps the wearer's attribute modifiers in sync once per second.</li>
 *     <li><b>Hide the per-equipment-slot attribute block for curios in the tooltip.</b> Two independent layers:
 *         {@code mixin.client.AttributesLibClientMixin} cancels
 *         {@code AttributesLibClient#applyModifierTooltips} at HEAD, and {@link AttributeTooltipFilter} removes
 *         the same "在任意手时 / 穿在脚下时 / ..." groups from the finished tooltip, so the fix survives a mixin
 *         that cannot be applied. Armor and weapons are untouched.</li>
 * </ol>
 *
 * <p>Nothing here touches {@code irons_apothic} or ApothicCurios: both keep working exactly as before, and
 * this mod does not even need ApothicCurios at runtime.</p>
 */
@Mod(ApothicCuriosFix.MODID)
public class ApothicCuriosFix {

    public static final String MODID = "apothiccuriosfix";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ApothicCuriosFix() {
        // Must happen before any data pack is parsed: affix/gem "types" fields are validated against
        // LootCategory.BY_ID during the reload, and ApothicCurios' own registration only happens when
        // LootCategory.byId("curios:...") is called later at runtime (and its mixin is inactive in the
        // "-optimized" rebuild anyway).
        // 3.0.0:配置文件(各模块可单独开关)
        AcfConfig.register();

        // ---- 类目注册 ----
        // iron_only 模式下,饰品模块强制关闭,忽略 curioFixEnabled
        boolean curioOn = AcfConfig.curioFix() && !AcfConfig.ironOnly();
        if (AcfConfig.ironOnly()) {
            LOGGER.info("[ApothicCuriosFix] 运行模式 = iron_only(只保留神化铁魔法部分)");
        }
        if (curioOn) {
            CurioLootCategories.register();
        } else {
            LOGGER.info("[ApothicCuriosFix] 神化饰品模块已关闭 —— 跳过 curios:* 类目注册");
        }
        if (AcfConfig.extraLootCategories()) {
            dev.hengx.apothiccuriosfix.category.ExtraLootCategories.register();
        } else {
            LOGGER.info("[ApothicCuriosFix] 类目补注册已关闭 —— staff/spellbook/light_weapon 不会注册");
        }

        // ---- 事件 ----
        if (curioOn) {
            MinecraftForge.EVENT_BUS.register(CurioAffixAttributeBridge.class);
            MinecraftForge.EVENT_BUS.register(CurioSlotReloadHandler.class);
            MinecraftForge.EVENT_BUS.register(AttributeTooltipFilter.class);
        }
        if (AcfConfig.independentAttrDamage()) {
            MinecraftForge.EVENT_BUS.register(dev.hengx.apothiccuriosfix.event.IndependentAttrDamageHandler.class);
        }
        if (AcfConfig.heavenSever()) {
            MinecraftForge.EVENT_BUS.register(dev.hengx.apothiccuriosfix.event.HeavenSeverHandler.class);
        }
        if (AcfConfig.weaknessBreaker()) {
            MinecraftForge.EVENT_BUS.register(dev.hengx.apothiccuriosfix.event.WeaknessBreakerHandler.class);
        }
        MinecraftForge.EVENT_BUS.register(SelfTest.class);
        MinecraftForge.EVENT_BUS.register(ClientSelfTest.class);
        MinecraftForge.EVENT_BUS.register(DiagCommand.class);

        // 1.20.1-merged: 神化铁魔法(irons_apothic)模块 —— 只有装了铁魔法才初始化。
        // 没有 Iron's Spellbooks 时:不注册 irons_apothic:* 的 affix codec,那批词条 JSON 会因为
        // "未知类型"而不被加载,等于整个模块不存在。
        if (!AcfConfig.ironSpellbooks()) {
            LOGGER.info("[ApothicCuriosFix] 神化铁魔法模块已在配置中关闭 —— 不加载 irons_apothic 词条");
        }
        else if (dev.muon.irons_apothic.IronsApothic.ironsLoaded()) {
            try {
                dev.muon.irons_apothic.IronsApothic.init();
            }
            catch (RuntimeException | LinkageError ex) {
                LOGGER.error("[ApothicCuriosFix] 神化铁魔法模块初始化失败(已跳过)", ex);
            }
        } else {
            LOGGER.info("[ApothicCuriosFix] 未安装 Iron's Spellbooks —— 神化铁魔法模块不加载");
        }

        Debug.log("loaded (ApothicCurios present: %s, curios present: %s)", loaded("apothiccurios"), loaded("curios"));
    }

    public static boolean loaded(String modId) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(modId);
    }
}
