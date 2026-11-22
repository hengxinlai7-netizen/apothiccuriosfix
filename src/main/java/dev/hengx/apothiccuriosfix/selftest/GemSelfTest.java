package dev.hengx.apothiccuriosfix.selftest;

import com.mojang.logging.LogUtils;
import dev.hengx.apothiccuriosfix.gem.GemCurioSupport;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.apotheosis.adventure.loot.RarityRegistry;
import dev.shadowsoffire.apotheosis.adventure.socket.SocketHelper;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.Gem;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemInstance;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemRegistry;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.bonus.GemBonus;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1.3.1 端到端自检:<b>程序化造一颗真宝石 → 塞进一件真饰品 → 看属性到底有没有产出</b>。
 *
 * <p>只在 {@code -Dapothiccuriosfix.selftest=true} 时由 {@code ApothicCuriosFix} 注册,正常游戏不加载。</p>
 *
 * <p>四项:</p>
 * <ul>
 *   <li><b>A</b> 白名单宝石镶饰品后 {@code GemInstance.isValid() == true};</li>
 *   <li><b>B</b> 我们的 bonus 路径(`GemCurioSupport.pick` → `GemBonus#addModifiers`)产出 ≥1 个修饰符,
 *       并且 {@code CurioAffixAttributeBridge} 依赖的同一个 consumer 能把它接住;</li>
 *   <li><b>C</b> 非白名单宝石镶饰品 → 仍然 0(白名单边界);</li>
 *   <li><b>D</b> 同一颗宝石镶原版胸甲 → 原生 {@code GemInstance#addModifiers(CHEST, ...)} 照常产出(回归)。</li>
 * </ul>
 */
public final class GemSelfTest {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String TAG = "[apothiccuriosfix][gem-selftest]";

    /** 白名单里的护甲类宝石(见 GemCurioSupport.ALLOWED_GEMS)。 */
    private static final ResourceLocation GOOD_GEM = new ResourceLocation("apotheosis", "core/guardian");
    /** 明确不在白名单里的宝石(仅用于边界对照)。 */
    private static final ResourceLocation OTHER_GEM = new ResourceLocation("apotheosis", "core/esoteric");
    /** 饰品:magnet_ring 在 curios:ring 的 item tag 里。 */
    private static final ResourceLocation RING = new ResourceLocation("enigmaticlegacy", "magnet_ring");

    private GemSelfTest() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            run();
        } catch (Throwable t) {
            log("EXCEPTION " + t);
        }
    }

    private static void run() {
        LootRarity rarity = RarityRegistry.INSTANCE.getValue(new ResourceLocation("apotheosis", "common"));
        log("rarity(apotheosis:common) = " + (rarity == null ? "null" : String.valueOf(rarity)));

        ItemStack ring = stackOf(RING);
        LootCategory ringCat = LootCategory.forItem(ring);
        log("ring = " + RING + "  empty=" + ring.isEmpty()
            + "  forItem=" + ringCat + "  isCurioItem=" + GemCurioSupport.isCurioItem(ring));

        Gem good = GemRegistry.INSTANCE.getValue(GOOD_GEM);
        // 非白名单宝石:直接从注册表里挑第一颗"不在白名单"的(不写死 id,避免版本差异)
        Gem other = null;
        try {
            for (Gem candidate : GemRegistry.INSTANCE.getValues()) {
                if (candidate != null && !GemCurioSupport.isAllowed(candidate)) {
                    other = candidate;
                    break;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            other = null;
        }
        log("gem(whitelisted) = " + GOOD_GEM + " -> " + (good == null ? "null" : "present")
            + "  isAllowed=" + (good != null && GemCurioSupport.isAllowed(good)));
        log("gem(non-whitelisted) = " + OTHER_GEM + " -> " + (other == null ? "null" : "present")
            + "  isAllowed=" + (other != null && GemCurioSupport.isAllowed(other)));

        if (good == null || other == null || rarity == null || ring.isEmpty()) {
            log("SKIP: 缺少宝石/品质/饰品,无法构造端到端场景");
            return;
        }

        // ---------- A + B:白名单宝石镶饰品 ----------
        ItemStack goodGem = GemRegistry.createGemStack(good, rarity);
        ItemStack socketedRing = ring.copy();
        SocketHelper.setSockets(socketedRing, 1);
        GemInstance inst = GemInstance.socketed(socketedRing, goodGem);
        boolean valid = inst.isValid();
        int produced = countModifiers(good, goodGem, rarity);
        log("A  GemInstance.isValid() = " + valid + "   (gem=" + GOOD_GEM + ", item=" + RING + ")");
        log("B  pick+addModifiers produced = " + produced + "   (bonus=" + bonusName(good, rarity) + ")");

        // 模拟 CurioAffixAttributeBridge 的 consumer,确认能接住
        AtomicInteger received = new AtomicInteger();
        GemBonus bonus = GemCurioSupport.pick(good, good.getBonuses(), rarity);
        if (bonus != null) {
            bonus.addModifiers(goodGem, rarity, (attribute, modifier) -> {
                if (attribute != null && modifier != null && modifier.getAmount() != 0.0D) {
                    received.incrementAndGet();
                }
            });
        }
        log("B2 bridge-consumer received = " + received.get());

        // ---------- C:非白名单宝石镶饰品 ----------
        ItemStack otherGem = GemRegistry.createGemStack(other, rarity);
        ItemStack otherRing = ring.copy();
        SocketHelper.setSockets(otherRing, 1);
        GemInstance otherInst = GemInstance.socketed(otherRing, otherGem);
        log("C  non-whitelisted isValid() = " + otherInst.isValid()
            + "   produced=" + countModifiers(other, otherGem, rarity) + "   (gem=" + OTHER_GEM + ")");

        // ---------- D:原版胸甲回归 ----------
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        SocketHelper.setSockets(chest, 1);
        GemInstance chestInst = GemInstance.socketed(chest, goodGem);
        AtomicInteger chestMods = new AtomicInteger();
        chestInst.addModifiers(EquipmentSlot.CHEST, (attribute, modifier) -> chestMods.incrementAndGet());
        log("D  chestplate forItem=" + LootCategory.forItem(chest) + "  isValid=" + chestInst.isValid()
            + "  native addModifiers(CHEST) = " + chestMods.get());

        boolean pass = valid && produced >= 1 && received.get() >= 1 && !otherInst.isValid()
            && countModifiers(other, otherGem, rarity) == 0 && chestInst.isValid() && chestMods.get() >= 1;
        log("RESULT=" + (pass ? "PASS" : "FAIL"));
    }

    /** 走本模组的取值路径,数一数能产出多少条属性修饰符。 */
    private static int countModifiers(Gem gem, ItemStack gemStack, LootRarity rarity) {
        GemBonus bonus = GemCurioSupport.pick(gem, gem.getBonuses(), rarity);
        if (bonus == null) {
            return 0;
        }
        AtomicInteger count = new AtomicInteger();
        bonus.addModifiers(gemStack, rarity, (attribute, modifier) -> {
            if (attribute != null && modifier != null && modifier.getAmount() != 0.0D) {
                count.incrementAndGet();
            }
        });
        return count.get();
    }

    private static String bonusName(Gem gem, LootRarity rarity) {
        GemBonus bonus = GemCurioSupport.pick(gem, gem.getBonuses(), rarity);
        return bonus == null ? "null" : bonus.getClass().getSimpleName();
    }

    private static ItemStack stackOf(ResourceLocation id) {
        var item = ForgeRegistries.ITEMS.getValue(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static void log(String message) {
        LOG.info("{} {}", TAG, message);
    }
}
