package com.assistantbot.gui;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * A fully server-side point-and-click menu. The container slots hold labeled
 * "button" items; clicks are intercepted in {@link Handler#onSlotClick} and never
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
    public static void open(ServerPlayerEntity player) {
        SimpleInventory inventory = new SimpleInventory(ROW_SIZE);
        inventory.setStack(SLOT_SUMMON, button(Items.LIME_CONCRETE, "§a✚ Summon Bot", "Spawn your bot"));
        inventory.setStack(SLOT_DISMISS, button(Items.RED_CONCRETE, "§c✖ Dismiss Bot", "Send your bot away"));
        inventory.setStack(SLOT_FOLLOW, button(Items.ARROW, "§a➜ Follow Me", "Bot follows you around"));
        inventory.setStack(SLOT_STOP, button(Items.REDSTONE_BLOCK, "§e■ Stop / Wait", "Bot stands still"));
        inventory.setStack(SLOT_BUILD, button(Items.CRAFTING_TABLE, "§b⚒ Build…", "Type what to build"));
        inventory.setStack(SLOT_STATUS, button(Items.COMPASS, "§f? Status", "What is the bot doing?"));

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, p) -> new Handler(syncId, playerInventory, inventory),
                Text.literal("Assistant Bot")));
    }

    private static ItemStack button(Item item, String name, String lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal(name).styled(s -> s.withItalic(false)));
        stack.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(java.util.List.of(
                Text.literal(lore).styled(s -> s.withItalic(false).withColor(Formatting.GRAY)))));
        return stack;
    }

    /**
     * Single-row container handler that turns slot clicks into bot actions.
     */
    public static class Handler extends GenericContainerScreenHandler {
        public Handler(int syncId, PlayerInventory playerInventory, Inventory inventory) {
            super(ScreenHandlerType.GENERIC_9X1, syncId, playerInventory, inventory, 1);
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            // Read-only menu: swallow every click. Run an action only for our button slots.
            if (player instanceof ServerPlayerEntity sp && slotIndex >= 0 && slotIndex < ROW_SIZE) {
                handleButton(slotIndex, sp);
            }
        }

        private void handleButton(int slot, ServerPlayerEntity sp) {
            switch (slot) {
                case SLOT_SUMMON -> BotActions.summon(sp);
                case SLOT_DISMISS -> BotActions.dismiss(sp);
                case SLOT_FOLLOW -> BotActions.follow(sp);
                case SLOT_STOP -> BotActions.stop(sp);
                case SLOT_STATUS -> BotActions.status(sp);
                case SLOT_BUILD ->
                    // Defer so we finish handling this click before swapping screens.
                    ((net.minecraft.server.world.ServerWorld) sp.getEntityWorld())
                            .getServer().execute(() -> BotBuildAnvil.open(sp));
                default -> { /* decorative / empty slot */ }
            }
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return true;
        }
    }
}
