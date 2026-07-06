package com.assistantbot.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A fully server-side point-and-click menu. The container slots hold labeled
 * "button" items; clicks are intercepted in {@link Handler#clicked} and never
 * forwarded to {@code super}, which both runs the mapped action and keeps the menu
 * read-only (no item can be moved, dropped, or shift-clicked out).
 *
 * Works on a vanilla client — it's just a chest GUI.
 */
public final class BotMenu {
    // Button slot indices within the single 9-slot row.
    private static final int SLOT_SUMMON = 0;
    private static final int SLOT_DISMISS = 1;
    private static final int SLOT_FOLLOW = 2;
    private static final int SLOT_STOP = 3;
    private static final int SLOT_BUILD = 5;
    private static final int SLOT_STATUS = 6;

    private static final int ROW_SIZE = 9;

    private BotMenu() {}

    /** Opens the bot menu for the player. */
    public static void open(ServerPlayer player) {
        SimpleContainer inventory = new SimpleContainer(ROW_SIZE);
        inventory.setItem(SLOT_SUMMON, button(Items.CONCRETE.lime(), "§a✚ Summon Bot", "Spawn your bot"));
        inventory.setItem(SLOT_DISMISS, button(Items.CONCRETE.red(), "§c✖ Dismiss Bot", "Send your bot away"));
        inventory.setItem(SLOT_FOLLOW, button(Items.ARROW, "§a➜ Follow Me", "Bot follows you around"));
        inventory.setItem(SLOT_STOP, button(Items.REDSTONE_BLOCK, "§e■ Stop / Wait", "Bot stands still"));
        inventory.setItem(SLOT_BUILD, button(Items.CRAFTING_TABLE, "§b⚒ Build…", "Type what to build"));
        inventory.setItem(SLOT_STATUS, button(Items.COMPASS, "§f? Status", "What is the bot doing?"));

        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, p) -> new Handler(syncId, playerInventory, inventory),
                Component.literal("Assistant Bot")));
    }

    private static ItemStack button(Item item, String name, String lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(name).withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                Component.literal(lore).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))));
        return stack;
    }

    /**
     * Single-row container handler that turns slot clicks into bot actions.
     */
    public static class Handler extends ChestMenu {
        public Handler(int syncId, Inventory playerInventory, Container inventory) {
            super(MenuType.GENERIC_9x1, syncId, playerInventory, inventory, 1);
        }

        @Override
        public void clicked(int slotIndex, int button, ContainerInput actionType, Player player) {
            // Read-only menu: swallow every click. Run an action only for our button slots.
            if (player instanceof ServerPlayer sp && slotIndex >= 0 && slotIndex < ROW_SIZE) {
                handleButton(slotIndex, sp);
            }
        }

        private void handleButton(int slot, ServerPlayer sp) {
            switch (slot) {
                case SLOT_SUMMON -> BotActions.summon(sp);
                case SLOT_DISMISS -> BotActions.dismiss(sp);
                case SLOT_FOLLOW -> BotActions.follow(sp);
                case SLOT_STOP -> BotActions.stop(sp);
                case SLOT_STATUS -> BotActions.status(sp);
                case SLOT_BUILD ->
                    // Defer so we finish handling this click before swapping screens.
                    ((net.minecraft.server.level.ServerLevel) sp.level())
                            .getServer().execute(() -> BotBuildAnvil.open(sp));
                default -> { /* decorative / empty slot */ }
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
