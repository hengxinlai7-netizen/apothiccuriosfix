package dev.muon.irons_apothic;

import com.mojang.logging.LogUtils;
import dev.muon.irons_apothic.affix.ManaCostAffix;
import dev.muon.irons_apothic.affix.HeavenSeverAffix;
import dev.muon.irons_apothic.affix.WeaknessBreakerAffix;
import dev.muon.irons_apothic.affix.RhythmStrikeAffix;
import dev.muon.irons_apothic.event.RhythmStrikeEvents;
import dev.muon.irons_apothic.affix.SchoolAttributeAffix;
import dev.muon.irons_apothic.category.CurioLootCategories;
import dev.muon.irons_apothic.event.CurioAffixAttributeBridge;
import dev.muon.irons_apothic.event.IronsManaCostHandler;
import dev.muon.irons_apothic.selftest.SelfTest;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

public class IronsApothic {

    public static final String MODID = "irons_apothic";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 由 {@code ApothicCuriosFix} 在**确认装了铁魔法之后**调用。
     * <p>整个模块都只在有 Iron's Spellbooks 时才初始化:没有铁魔法时:
     * <ul>
     *   <li>不注册 affix codec({@code irons_apothic:school_attribute} / {@code irons_apothic:mana_cost}),
     *       于是 data/irons_apothic 与 data/irons_apothic_extra 里的词条因为"未知类型"根本不会被加载;</li>
     *   <li>不注册任何引用铁魔法类型的监听器({@code IronsManaCostHandler} 引用了 SpellOnCastEvent/SchoolType);</li>
     *   <li>不碰 Curios 的 loot category 之外的任何东西。</li>
     * </ul>
     */
    public static void init() {
        if (!ironsLoaded()) {
            LOGGER.info("[IronsApothic] 未安装 Iron's Spellbooks —— 神化铁魔法模块不加载");
            return;
        }
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(IronsApothic::commonSetup);

        // Loot categories must exist before any data pack (affix "types" fields) is parsed.
        CurioLootCategories.register();

        MinecraftForge.EVENT_BUS.register(CurioAffixAttributeBridge.class);
        MinecraftForge.EVENT_BUS.register(IronsManaCostHandler.class);
        MinecraftForge.EVENT_BUS.register(RhythmStrikeEvents.class);

        LOGGER.info("[IronsApothic] loading 1.20.1 port (Iron's Spellbooks present: {})", ironsLoaded());
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            AffixRegistry.INSTANCE.registerCodec(loc("school_attribute"), SchoolAttributeAffix.CODEC);
            AffixRegistry.INSTANCE.registerCodec(loc("mana_cost"), ManaCostAffix.CODEC);
        try {
            AffixRegistry.INSTANCE.registerCodec(loc("rhythm_strike"), RhythmStrikeAffix.CODEC);
            AffixRegistry.INSTANCE.registerCodec(loc("heaven_sever"), HeavenSeverAffix.CODEC);
            AffixRegistry.INSTANCE.registerCodec(loc("weakness_breaker"), WeaknessBreakerAffix.CODEC);
        }
        catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[IronsApothic] rhythm_strike codec 注册失败(不影响其它词条): {}", e.toString());
        }
            LOGGER.info("[IronsApothic] registered affix codecs {} and {}", loc("school_attribute"), loc("mana_cost"));
        });
    }

    public static boolean ironsLoaded() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded("irons_spellbooks");
    }

    public static ResourceLocation loc(String path) {
        return new ResourceLocation(MODID, path);
    }
}
