package dev.muon.irons_apothic.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.util.ArrayList;
import java.util.List;

/** Collects every stack a living entity has "equipped", including curios slots. */
public final class PlayerGear {

    private PlayerGear() {
    }

    public static List<ItemStack> equippedAndCurios(LivingEntity entity) {
        List<ItemStack> stacks = new ArrayList<>();
        if (entity instanceof Player player) {
            for (ItemStack stack : player.getInventory().items) {
                stacks.add(stack);
            }
            for (ItemStack stack : player.getInventory().armor) {
                stacks.add(stack);
            }
            for (ItemStack stack : player.getInventory().offhand) {
                stacks.add(stack);
            }
        } else {
            for (ItemStack stack : entity.getAllSlots()) {
                stacks.add(stack);
            }
        }
        for (SlotResult result : curios(entity)) {
            stacks.add(result.stack());
        }
        return stacks;
    }

    public static List<SlotResult> curios(LivingEntity entity) {
        return CuriosApi.getCuriosInventory(entity)
                .map(handler -> handler.findCurios(stack -> !stack.isEmpty()))
                .orElse(List.of());
    }
}
