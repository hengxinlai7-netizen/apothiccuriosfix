package dev.muon.irons_apothic.affix;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 「节奏打击」的运行时逻辑。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li>施放法术时记录 {@code (玩家, 法术 id)} 与时间;</li>
 *   <li>若与上一次是<b>同一个法术</b>且间隔 ≤ 10 秒 → 层数 +1，并把计时<b>刷新</b>为 10 秒;</li>
 *   <li>换法术、或超过 10 秒没再放 → 层数归零，从 1 重新计;</li>
 *   <li>每层给 {@code irons_spellbooks:spell_power} 挂 {@code +10%}(MULTIPLY_TOTAL)，<b>无上限</b>。</li>
 * </ul>
 *
 * <p>只有主手/副手物品带 {@code irons_apothic:rhythm_strike} 词条时才累计。</p>
 */
public final class RhythmStrikeHandler {

    /** 10 秒 = 200 tick。 */
    public static final int WINDOW_TICKS = 200;

    /** 每层的法强加成。 */
    public static final double PER_STACK = 0.10D;

    /** 层数上限(5 层 = +50% 法强)。 */
    public static final int MAX_STACKS = 5;

    /** 词条 id(用于判断物品是否带这个词条)。 */
    public static final ResourceLocation AFFIX_ID =
        new ResourceLocation("irons_apothic", "rhythm_strike");

    /** 法强属性 id。 */
    private static final ResourceLocation SPELL_POWER_ID =
        new ResourceLocation("irons_spellbooks", "spell_power");

    /** 修饰符固定 UUID。 */
    private static final UUID MODIFIER_ID =
        UUID.nameUUIDFromBytes("irons_apothic:rhythm_strike_spell_power".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /** 玩家状态。 */
    private record State(String spellId, long lastCast, int stacks) {
    }

    private static final Map<UUID, State> STATE = new ConcurrentHashMap<>();

    private RhythmStrikeHandler() {
    }

    /** 当前层数(供 tooltip / 自检用)。 */
    public static int stacks(Player player) {
        if (player == null) {
            return 0;
        }
        State s = STATE.get(player.getUUID());
        if (s == null) {
            return 0;
        }
        if (player.level().getGameTime() - s.lastCast() > WINDOW_TICKS) {
            return 0;
        }
        return s.stacks();
    }

    /** 施法时调用。 */
    public static void onCast(Player player, String spellId) {
        if (player == null || spellId == null || player.level().isClientSide) {
            return;
        }
        long now = player.level().getGameTime();
        State prev = STATE.get(player.getUUID());
        int stacks;
        if (prev != null && spellId.equals(prev.spellId()) && now - prev.lastCast() <= WINDOW_TICKS) {
            stacks = Math.min(MAX_STACKS, prev.stacks() + 1);
        }
        else {
            stacks = 1;
        }
        // 每次施放都刷新计时
        STATE.put(player.getUUID(), new State(spellId, now, stacks));
        applyModifier(player, stacks);
    }

    /** 每 tick 检查超时(超出 10 秒就清空)。 */
    public static void tick(Player player) {
        if (player == null || player.level().isClientSide || player.tickCount % 20 != 0) {
            return;
        }
        State s = STATE.get(player.getUUID());
        if (s == null) {
            return;
        }
        if (player.level().getGameTime() - s.lastCast() > WINDOW_TICKS) {
            STATE.remove(player.getUUID());
            applyModifier(player, 0);
        }
    }

    /** 把层数写成一条法强修饰符(先删后加,幂等)。 */
    private static void applyModifier(Player player, int stacks) {
        try {
            Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(SPELL_POWER_ID);
            if (attr == null) {
                return;
            }
            AttributeInstance inst = player.getAttribute(attr);
            if (inst == null) {
                return;
            }
            for (AttributeModifier mod : new java.util.ArrayList<>(inst.getModifiers())) {
                if (mod.getId().equals(MODIFIER_ID)) {
                    inst.removeModifier(mod.getId());
                }
            }
            if (stacks > 0) {
                inst.addPermanentModifier(new AttributeModifier(MODIFIER_ID,
                    "irons_apothic:rhythm_strike", PER_STACK * stacks,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 铁魔法缺失时静默跳过
        }
    }
}
