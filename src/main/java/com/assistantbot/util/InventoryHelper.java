package com.assistantbot.util;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.*;

/**
 * Inventory operations for equipping tools/weapons and transferring items.
 * Mirrors the plan-then-execute pattern from the inspiration bot.
 */
public final class InventoryHelper {
    private InventoryHelper() {}

    public static void equipBestTool(FakePlayer player, BlockState targetBlock) {
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

    public static void equipBestWeapon(FakePlayer player) {
        PlayerInventory inv = player.getInventory();
        int bestSlot = -1;
        int bestPriority = 0;

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            int priority = 0;
            Item item = stack.getItem();
            if (item instanceof SwordItem) {
                priority = 3;
            } else if (item instanceof AxeItem) {
                priority = 2;
            }

            if (priority > bestPriority) {
                bestPriority = priority;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            moveToHand(inv, bestSlot);
        }
    }

    public static boolean equipItem(FakePlayer player, Item targetItem) {
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

    public static int countItem(FakePlayer player, Item targetItem) {
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

    public static void depositAll(FakePlayer player, Inventory container) {
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
            inv.selectedSlot = sourceSlot;
        } else {
            ItemStack source = inv.getStack(sourceSlot);
            ItemStack hand = inv.getStack(inv.selectedSlot);
            inv.setStack(sourceSlot, hand);
            inv.setStack(inv.selectedSlot, source);
        }
    }

    private static boolean canStack(ItemStack a, ItemStack b) {
        return ItemStack.canCombine(a, b);
    }
}
