package com.assistantbot.gui;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

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
    public static void open(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, p) -> new Handler(syncId, inventory),
                Text.literal("Type your build, then take the paper →")));
    }

    public static class Handler extends AnvilScreenHandler {
        private String typedName = "";

        public Handler(int syncId, PlayerInventory playerInventory) {
            super(syncId, playerInventory, ScreenHandlerContext.EMPTY);
            // Seed the left slot so the anvil has something to "rename" into output.
            ItemStack prompt = new ItemStack(Items.PAPER);
            prompt.set(DataComponentTypes.CUSTOM_NAME,
                    Text.literal("a house").styled(s -> s.withItalic(false)));
            this.input.setStack(0, prompt);
        }

        @Override
        public boolean setNewItemName(String newName) {
            this.typedName = newName == null ? "" : newName;
            return super.setNewItemName(newName);
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return true; // opened without a real anvil block
        }

        @Override
        protected boolean canTakeOutput(PlayerEntity player, boolean present) {
            return present; // no XP cost required
        }

        @Override
        protected void onTakeOutput(PlayerEntity player, ItemStack stack) {
            String description = typedName.trim();
            this.setCursorStack(ItemStack.EMPTY); // don't hand the player the paper
            if (player instanceof ServerPlayerEntity sp) {
                sp.closeHandledScreen();
                if (!description.isEmpty()) {
                    ((net.minecraft.server.world.ServerWorld) sp.getEntityWorld())
                            .getServer().execute(() -> BotActions.build(sp, description));
                } else {
                    sp.sendMessage(Text.literal("§e[Assistant] No build text entered."), false);
                }
            }
        }
    }
}
