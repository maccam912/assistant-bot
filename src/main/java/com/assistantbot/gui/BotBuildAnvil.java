package com.assistantbot.gui;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Server-side free-text input for builds, via an anvil's rename box. The player
 * types a build description into the anvil text field and takes the output item;
 * that text is forwarded to {@link BotActions#build}.
 *
 * The anvil is opened detached from any block, so {@link Handler} overrides
 * {@code canUse}/{@code canTakeOutput} to bypass the vanilla anvil-block and XP
 * requirements.
 */
public final class BotBuildAnvil {
    private BotBuildAnvil() {}

    /** Opens the build text-input anvil for the player. */
    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, p) -> new Handler(syncId, inventory),
                Component.literal("Type your build, then take the paper →")));
    }

    public static class Handler extends AnvilMenu {
        private String typedName = "";

        public Handler(int syncId, Inventory playerInventory) {
            super(syncId, playerInventory, ContainerLevelAccess.NULL);
            // Seed the left slot so the anvil has something to "rename" into output.
            ItemStack prompt = new ItemStack(Items.PAPER);
            prompt.set(DataComponents.CUSTOM_NAME,
                    Component.literal("a house").withStyle(s -> s.withItalic(false)));
            this.inputSlots.setItem(0, prompt);
        }

        @Override
        public boolean setItemName(String newName) {
            this.typedName = newName == null ? "" : newName;
            return super.setItemName(newName);
        }

        @Override
        public boolean stillValid(Player player) {
            return true; // opened without a real anvil block
        }

        @Override
        protected boolean mayPickup(Player player, boolean present) {
            return present; // no XP cost required
        }

        @Override
        protected void onTake(Player player, ItemStack stack) {
            String description = typedName.trim();
            this.setCarried(ItemStack.EMPTY); // don't hand the player the paper
            if (player instanceof ServerPlayer sp) {
                sp.closeContainer();
                if (!description.isEmpty()) {
                    ((net.minecraft.server.level.ServerLevel) sp.level())
                            .getServer().execute(() -> BotActions.build(sp, description));
                } else {
                    sp.sendSystemMessage(Component.literal("§e[Assistant] No build text entered."), false);
                }
            }
        }
    }
}
