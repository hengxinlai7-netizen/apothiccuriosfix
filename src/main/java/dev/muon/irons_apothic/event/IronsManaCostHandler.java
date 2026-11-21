package dev.muon.irons_apothic.event;

import dev.muon.irons_apothic.affix.ManaCostAffix;
import dev.muon.irons_apothic.util.Debug;
import dev.muon.irons_apothic.util.PlayerGear;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;

/**
 * Applies {@link ManaCostAffix} to Iron's Spells casts.
 *
 * <p>Why this event: 1.20.1 Iron's {@code AbstractSpell#castSpell} posts {@code SpellOnCastEvent} with the
 * spell's mana cost, then subtracts {@code event.getManaCost()} from the caster's {@code MagicData}. Both
 * {@code getManaCost()} and {@code setManaCost(int)} are public on 3.15.4, so lowering the cost here is enough
 * and no mixin into the casting path is required. (Verified with {@code javap} against
 * {@code irons_spellbooks.jar} 1.20.1-3.15.4.)</p>
 *
 * <p>Known limitation: Iron's checks "do I have enough mana to start this cast?" against the <em>unreduced</em>
 * cost before the event fires, so a reduction can lower the actual spend but cannot enable a cast while the
 * caster is below the base cost.</p>
 */
public final class IronsManaCostHandler {

    private IronsManaCostHandler() {
    }

    @SubscribeEvent
    public static void onSpellCast(SpellOnCastEvent event) {
        Player player = event.getEntity();
        if (player == null || player.level().isClientSide()) {
            return;
        }
        SchoolType school = event.getSchoolType();
        ResourceLocation schoolId = school == null ? null : school.getId();

        float reduction = ManaCostAffix.totalReduction(PlayerGear.equippedAndCurios(player), schoolId);
        if (reduction <= 0.0F) {
            return;
        }
        int baseCost = event.getManaCost();
        int reduced = ManaCostAffix.applyReduction(baseCost, reduction);
        event.setManaCost(reduced);
        Debug.log("mana cost of %s (%s): %d -> %d, reduction=%.3f",
                event.getSpellId(), schoolId, baseCost, reduced, reduction);
    }

    /** Convenience for diagnostics: the reduction the given player currently has for a school. */
    public static float reductionFor(Player player, @Nullable ResourceLocation schoolId) {
        return ManaCostAffix.totalReduction(PlayerGear.equippedAndCurios(player), schoolId);
    }
}
