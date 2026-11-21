package dev.muon.irons_apothic.event;

import dev.muon.irons_apothic.affix.RhythmStrikeHandler;

import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 「节奏打击」的事件接线。
 *
 * <ul>
 *   <li>{@link SpellOnCastEvent} —— 每次施法累计/刷新层数;</li>
 *   <li>{@link TickEvent.PlayerTickEvent} —— 每 20 tick 检查是否已超时 10 秒。</li>
 * </ul>
 */
public final class RhythmStrikeEvents {

    private RhythmStrikeEvents() {
    }

    @SubscribeEvent
    public static void onCast(SpellOnCastEvent event) {
        try {
            if (event.getEntity() instanceof Player) {
                RhythmStrikeHandler.onCast((Player) event.getEntity(), event.getSpellId());
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 铁魔法缺失时跳过
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        RhythmStrikeHandler.tick(event.player);
    }
}
