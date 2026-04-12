package com.assistantbot.util;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.*;

/**
 * Inventory operations for equipping tools/weapons and transferring items.
 * Mirrors the plan-then-execute pattern from the inspiration bot.
 */
public final class InventoryHelper {
    private InventoryHelper() {}

    public static void equipBestTool(ServerPlayerEntity player, BlockState targetBlock) {
        PlayerInventory inv = player.getInventory();
        int bestSlot = -1;
        float bestSpeed = 1.0f;

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            float speed = stack.getMiningSpeedMultiplier(targetBlock);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            moveToHand(inv, bestSlot);
        }
    }

    public static void equipBestWeapon(ServerPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        int bestSlot = -1;
        double bestDamage = 0.0;
        boolean bestIsSword = false;

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            boolean isSword = stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS);
            boolean isAxe = item instanceof AxeItem;
            if (!isSword && !isAxe) continue;

            double damage = getAttackDamage(stack);

            // Prefer higher damage; tie-break: prefer swords over axes
            if (damage > bestDamage || (damage == bestDamage && isSword && !bestIsSword)) {
                bestDamage = damage;
                bestSlot = i;
                bestIsSword = isSword;
            }
        }

        if (bestSlot >= 0) {
            moveToHand(inv, bestSlot);
        }
    }

    /**
     * Extract the effective attack damage from an item stack by reading its
     * attribute modifiers for the main hand slot. Returns base damage (1.0)
     * plus all ATTACK_DAMAGE modifiers.
     *
     * Uses 1.21's callback-based applyAttributeModifiers API (the 1.20.x
     * Multimap-based getAttributeModifiers does not exist in 1.21).
     */
    private static double getAttackDamage(ItemStack stack) {
        // Use an array so we can mutate from inside the lambda
        double[] damage = {1.0}; // base attack damage

        stack.applyAttributeModifiers(EquipmentSlot.MAINHAND,
                (attribute, modifier) -> {
                    if (attribute == net.minecraft.entity.attribute.EntityAttributes.ATTACK_DAMAGE
                            && modifier.operation() == net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADD_VALUE) {
                        damage[0] += modifier.value();
                    }
                });

        return damage[0];
    }

    public static boolean equipItem(ServerPlayerEntity player, Item targetItem) {
        PlayerInventory inv = player.getInventory();

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                moveToHand(inv, i);
                return true;
            }
        }
        return false;
    }

    public static int countItem(ServerPlayerEntity player, Item targetItem) {
        PlayerInventory inv = player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static void depositAll(ServerPlayerEntity player, Inventory container) {
        PlayerInventory playerInv = player.getInventory();

        for (int playerSlot = 0; playerSlot < playerInv.size(); playerSlot++) {
            ItemStack stack = playerInv.getStack(playerSlot);
            if (stack.isEmpty()) continue;

            for (int containerSlot = 0; containerSlot < container.size(); containerSlot++) {
                ItemStack containerStack = container.getStack(containerSlot);

                if (containerStack.isEmpty()) {
                    container.setStack(containerSlot, stack.copy());
                    playerInv.setStack(playerSlot, ItemStack.EMPTY);
                    break;
                } else if (canStack(containerStack, stack)
                        && containerStack.getCount() < containerStack.getMaxCount()) {
                    int space = containerStack.getMaxCount() - containerStack.getCount();
                    int transfer = Math.min(space, stack.getCount());
                    containerStack.increment(transfer);
                    stack.decrement(transfer);
                    if (stack.isEmpty()) {
                        playerInv.setStack(playerSlot, ItemStack.EMPTY);
                        break;
                    }
                }
            }
        }
        container.markDirty();
    }

    // --- internal helpers ---

    private static void moveToHand(PlayerInventory inv, int sourceSlot) {
        if (sourceSlot < 9) {
            inv.setSelectedSlot(sourceSlot);
        } else {
            ItemStack source = inv.getStack(sourceSlot);
            ItemStack hand = inv.getStack(inv.getSelectedSlot());
            inv.setStack(sourceSlot, hand);
            inv.setStack(inv.getSelectedSlot(), source);
        }
    }

    private static boolean canStack(ItemStack a, ItemStack b) {
        return ItemStack.areItemsAndComponentsEqual(a, b);
    }
}
