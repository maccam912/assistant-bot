package com.assistantbot.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Inventory operations for equipping tools/weapons and transferring items.
 * Mirrors the plan-then-execute pattern from the inspiration bot.
 */
public final class InventoryHelper {
    private InventoryHelper() {}

    public static void equipBestTool(ServerPlayer player, BlockState targetBlock) {
        Inventory inv = player.getInventory();
        int bestSlot = -1;
        float bestSpeed = 1.0f;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            float speed = stack.getDestroySpeed(targetBlock);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            moveToHand(inv, bestSlot);
        }
    }

    public static void equipBestWeapon(ServerPlayer player) {
        Inventory inv = player.getInventory();
        int bestSlot = -1;
        double bestDamage = 0.0;
        boolean bestIsSword = false;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            boolean isSword = stack.is(net.minecraft.tags.ItemTags.SWORDS);
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
     * Uses the callback-based item modifier API; older Multimap-based
     * attribute modifier accessors are not available on current Minecraft.
     */
    private static double getAttackDamage(ItemStack stack) {
        // Use an array so we can mutate from inside the lambda
        double[] damage = {1.0}; // base attack damage

        stack.forEachModifier(EquipmentSlot.MAINHAND,
                (attribute, modifier) -> {
                    if (attribute == net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE
                            && modifier.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) {
                        damage[0] += modifier.amount();
                    }
                });

        return damage[0];
    }

    public static boolean equipItem(ServerPlayer player, Item targetItem) {
        Inventory inv = player.getInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                moveToHand(inv, i);
                return true;
            }
        }
        return false;
    }

    public static int countItem(ServerPlayer player, Item targetItem) {
        Inventory inv = player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static void depositAll(ServerPlayer player, Container container) {
        Inventory playerInv = player.getInventory();

        for (int playerSlot = 0; playerSlot < playerInv.getContainerSize(); playerSlot++) {
            ItemStack stack = playerInv.getItem(playerSlot);
            if (stack.isEmpty()) continue;

            for (int containerSlot = 0; containerSlot < container.getContainerSize(); containerSlot++) {
                ItemStack containerStack = container.getItem(containerSlot);

                if (containerStack.isEmpty()) {
                    container.setItem(containerSlot, stack.copy());
                    playerInv.setItem(playerSlot, ItemStack.EMPTY);
                    break;
                } else if (canStack(containerStack, stack)
                        && containerStack.getCount() < containerStack.getMaxStackSize()) {
                    int space = containerStack.getMaxStackSize() - containerStack.getCount();
                    int transfer = Math.min(space, stack.getCount());
                    containerStack.grow(transfer);
                    stack.shrink(transfer);
                    if (stack.isEmpty()) {
                        playerInv.setItem(playerSlot, ItemStack.EMPTY);
                        break;
                    }
                }
            }
        }
        container.setChanged();
    }

    // --- internal helpers ---

    private static void moveToHand(Inventory inv, int sourceSlot) {
        if (sourceSlot < 9) {
            inv.setSelectedSlot(sourceSlot);
        } else {
            ItemStack source = inv.getItem(sourceSlot);
            ItemStack hand = inv.getItem(inv.getSelectedSlot());
            inv.setItem(sourceSlot, hand);
            inv.setItem(inv.getSelectedSlot(), source);
        }
    }

    private static boolean canStack(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(a, b);
    }
}
